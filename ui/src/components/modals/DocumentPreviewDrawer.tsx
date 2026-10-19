import { useEffect, useRef, useState, type RefObject } from "react";
import { Alert, Button, Drawer, Empty, Radio, Result, Spin, Typography } from "antd";
import { CodeOutlined, FileTextOutlined, ReloadOutlined } from "@ant-design/icons";
import XMarkdown, { type ComponentProps, type XMarkdownProps } from "@ant-design/x-markdown";
import { describeDocumentContentFailure, getDocumentContent, type DocumentContentFailure } from "../../api/api.ts";
import type { CitationSource } from "../../types";
import "./DocumentPreviewDrawer.css";

export interface DocumentPreviewTarget {
  id: string;
  filename: string;
}

interface DocumentPreviewDrawerProps {
  document: DocumentPreviewTarget;
  /** 来自服务端校验过的引用；没有引用时按普通预览打开整份文档。 */
  citation?: CitationSource | null;
  onClose: () => void;
}

/**
 * 引用位置的核验结果。
 * - verified：来源哈希与引用一致，可按行号高亮；
 * - updated：文档已改动或行号越界，不能核验旧位置；
 * - unverified：当前环境无法做哈希校验（非安全上下文），只提示行号、不高亮；
 * - no-position：旧版索引只有文档 ID，打开整份文档并说明没有精确位置。
 */
type CitationLocation =
  | { state: "none" }
  | { state: "no-position" }
  | { state: "verified"; startLine: number; endLine: number }
  | { state: "unverified"; startLine: number; endLine: number }
  | { state: "updated" };

type PreviewResult = {
  documentId: string;
  attempt: number;
  /** 文档 + 引用位置：迟到的响应不能覆盖当前正在看的文档。 */
  requestKey: string;
} & (
  | { status: "ready"; filename: string; content: string; location: CitationLocation }
  | { status: "error"; kind: DocumentContentFailure; detail: string; retryable: boolean }
);

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (character) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  })[character]!);
}

function safeUrl(value: unknown, allowEmail = false): string | undefined {
  if (typeof value !== "string" || !value.trim()) return undefined;
  try {
    const url = new URL(value, window.location.href);
    if (url.protocol === "https:" || url.protocol === "http:" ||
      (allowEmail && url.protocol === "mailto:")) {
      return value;
    }
  } catch {
    // Invalid and unsupported URLs remain plain text in the preview.
  }
  return undefined;
}

function MarkdownLink({ href, title, children }: ComponentProps) {
  const url = safeUrl(href, true);
  if (!url) return <span>{children}</span>;
  return (
    <a
      href={url}
      title={typeof title === "string" ? title : undefined}
      target={url.startsWith("#") ? undefined : "_blank"}
      rel="noopener noreferrer"
    >
      {children}
    </a>
  );
}

function MarkdownImage({ src, alt, title }: ComponentProps) {
  const url = safeUrl(src);
  const description = typeof alt === "string" ? alt : "";
  if (!url) return <span>{description}</span>;
  return (
    <img
      src={url}
      alt={description}
      title={typeof title === "string" ? title : undefined}
      loading="lazy"
      referrerPolicy="no-referrer"
    />
  );
}

function MarkdownTable({ children }: ComponentProps) {
  return (
    <div className="document-preview-table" tabIndex={0} role="region" aria-label="表格">
      <table>{children}</table>
    </div>
  );
}

function MarkdownCodeBlock({ children }: ComponentProps) {
  return <pre tabIndex={0}>{children}</pre>;
}

const MARKDOWN_CONFIG: XMarkdownProps["config"] = {
  gfm: true,
  renderer: {
    html: ({ text }) => escapeHtml(text),
  },
};

const MARKDOWN_COMPONENTS: XMarkdownProps["components"] = {
  a: MarkdownLink,
  img: MarkdownImage,
  table: MarkdownTable,
  pre: MarkdownCodeBlock,
};

const SANITIZE_CONFIG: XMarkdownProps["dompurifyConfig"] = {
  ALLOWED_TAGS: [
    "p", "br", "strong", "em", "del", "blockquote", "ul", "ol", "li", "hr",
    "h1", "h2", "h3", "h4", "h5", "h6", "a", "img", "pre", "code", "table",
    "thead", "tbody", "tr", "th", "td", "input",
  ],
  ALLOWED_ATTR: ["href", "src", "alt", "title", "class", "align", "start", "type", "checked", "disabled"],
  ALLOW_DATA_ATTR: false,
  ALLOW_ARIA_ATTR: false,
  ALLOW_UNKNOWN_PROTOCOLS: false,
};

const STREAMING_CONFIG = { enableAnimation: false, hasNextChunk: false };

/** 与共享契约 §3 的哈希口径一致：去掉首个 BOM、CRLF/CR 统一为 LF。只用于比对，不改变展示的换行。 */
function normalizeSourceText(text: string): string {
  return text.replace(/^\uFEFF/, "").replace(/\r\n?/g, "\n");
}

async function sha256Hex(text: string): Promise<string | null> {
  const subtle = globalThis.crypto?.subtle;
  if (!subtle) return null;
  try {
    const digest = await subtle.digest("SHA-256", new TextEncoder().encode(text));
    return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
  } catch {
    return null;
  }
}

async function locateCitation(content: string, citation: CitationSource | null): Promise<CitationLocation> {
  if (!citation) return { state: "none" };
  const { sourceHash, startLine, endLine } = citation;
  if (sourceHash == null || startLine == null || endLine == null) return { state: "no-position" };
  const normalized = normalizeSourceText(content);
  const lineCount = normalized.split("\n").length;
  if (startLine < 1 || endLine < startLine || endLine > lineCount) return { state: "updated" };
  const actual = await sha256Hex(normalized);
  if (actual == null) return { state: "unverified", startLine, endLine };
  if (actual.toLowerCase() !== sourceHash.toLowerCase()) return { state: "updated" };
  return { state: "verified", startLine, endLine };
}

function requestKeyOf(documentId: string, citation: CitationSource | null | undefined): string {
  if (!citation) return `${documentId}|plain`;
  return [
    documentId,
    citation.sourceHash ?? "",
    citation.startLine ?? "",
    citation.endLine ?? "",
  ].join("|");
}

function failureTitle(kind: DocumentContentFailure): string {
  switch (kind) {
    case "unavailable":
      return "来源不可用";
    case "forbidden":
      return "无权访问该来源";
    default:
      return "暂时无法加载";
  }
}

function CitationNotice({
  location,
  onShowSource,
}: {
  location: CitationLocation;
  onShowSource: () => void;
}) {
  if (location.state === "none") return null;
  if (location.state === "verified") {
    return (
      <Alert
        className="mb-3"
        type="success"
        showIcon
        message={`引用位置：第 ${location.startLine}–${location.endLine} 行`}
        description="已按来源哈希校验，源码视图会高亮该范围。"
        action={<Button size="small" onClick={onShowSource}>在源码中查看</Button>}
      />
    );
  }
  if (location.state === "updated") {
    return (
      <Alert
        className="mb-3"
        type="warning"
        showIcon
        message="文档已更新，原引用位置不可核验"
        description="下面显示的是文档的当前版本，未对原引用范围做高亮。"
      />
    );
  }
  if (location.state === "unverified") {
    return (
      <Alert
        className="mb-3"
        type="warning"
        showIcon
        message="无法核验来源哈希"
        description={`当前环境不支持在浏览器内校验来源哈希；未核验的引用位置：第 ${location.startLine}–${location.endLine} 行。`}
      />
    );
  }
  return (
    <Alert
      className="mb-3"
      type="info"
      showIcon
      message="该来源没有精确位置"
      description="旧版索引只登记了文档本身，已为你打开整份文档。"
    />
  );
}

export default function DocumentPreviewDrawer({ document, citation = null, onClose }: DocumentPreviewDrawerProps) {
  const [mode, setMode] = useState<"preview" | "source">(() =>
    citation?.sourceHash && citation.startLine != null && citation.endLine != null ? "source" : "preview");
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<PreviewResult | null>(null);
  const highlightRef = useRef<HTMLElement | null>(null);
  const requestKey = requestKeyOf(document.id, citation);
  const current = result?.documentId === document.id && result.attempt === attempt &&
    result.requestKey === requestKey ? result : null;

  useEffect(() => {
    const controller = new AbortController();
    const key = requestKeyOf(document.id, citation);
    void getDocumentContent(document.id, controller.signal)
      .then(async (response) => {
        if (controller.signal.aborted) return;
        if (response.documentId !== document.id || typeof response.content !== "string" ||
          typeof response.filename !== "string") {
          throw new Error("文档内容无效");
        }
        const location = await locateCitation(response.content, citation);
        if (controller.signal.aborted) return;
        setResult({
          documentId: document.id,
          attempt,
          requestKey: key,
          status: "ready",
          filename: response.filename,
          content: response.content,
          location,
        });
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        const failure = describeDocumentContentFailure(error);
        setResult({
          documentId: document.id,
          attempt,
          requestKey: key,
          status: "error",
          kind: failure.kind,
          detail: failure.detail,
          retryable: failure.kind === "temporary",
        });
      });
    return () => controller.abort();
  }, [document.id, attempt, citation]);

  // 打开源码视图后把高亮范围滚到可视区域；渲染视图不做 DOM 猜测定位。
  useEffect(() => {
    if (mode !== "source") return;
    const target = highlightRef.current;
    if (!target) return;
    const timer = setTimeout(() => target.scrollIntoView({ block: "center" }), 0);
    return () => clearTimeout(timer);
  }, [mode, current]);

  return (
    <Drawer
      open
      onClose={onClose}
      placement="right"
      size={960}
      rootClassName="document-preview-drawer"
      title={
        <div className="min-w-0">
          <div className="mb-1 text-xs font-normal text-slate-500">{citation ? "引用来源" : "文档预览"}</div>
          <div className="document-preview-filename">
            {current?.status === "ready" ? current.filename : document.filename}
          </div>
        </div>
      }
      extra={<Button onClick={onClose}>关闭</Button>}
      styles={{
        wrapper: { maxWidth: "100vw" },
        body: { display: "flex", flexDirection: "column", padding: 0, overflow: "hidden" },
      }}
    >
      <div className="document-preview-toolbar">
        <Radio.Group
          aria-label="阅读方式"
          value={mode}
          optionType="button"
          buttonStyle="solid"
          onChange={(event) => setMode(event.target.value)}
          options={[
            { value: "preview", label: <><FileTextOutlined /> 预览</> },
            { value: "source", label: <><CodeOutlined /> 源码</> },
          ]}
        />
        <Typography.Text type="secondary">只读</Typography.Text>
      </div>
      <div
        key={`${document.id}:${attempt}:${mode}`}
        className="document-preview-scroll"
        aria-busy={!current}
      >
        {!current ? (
          <div className="document-preview-status" role="status">
            <Spin size="large" />
            <Typography.Text type="secondary">正在加载文档…</Typography.Text>
          </div>
        ) : current.status === "error" ? (
          <Result
            status="warning"
            title={failureTitle(current.kind)}
            subTitle={current.detail}
            extra={current.retryable
              ? <Button icon={<ReloadOutlined />} onClick={() => setAttempt((value) => value + 1)}>重试</Button>
              : undefined}
          />
        ) : (
          <>
            <CitationNotice location={current.location} onShowSource={() => setMode("source")} />
            {current.content.length === 0 ? (
              <div className="document-preview-status"><Empty description="这份文档暂无内容" /></div>
            ) : mode === "source" ? (
              <pre className="document-preview-source" tabIndex={0} aria-label="Markdown 源码">
                {renderSource(current.content, current.location, highlightRef)}
              </pre>
            ) : (
              <XMarkdown
                className="document-preview-markdown"
                config={MARKDOWN_CONFIG}
                components={MARKDOWN_COMPONENTS}
                dompurifyConfig={SANITIZE_CONFIG}
                streaming={STREAMING_CONFIG}
                content={current.content}
              />
            )}
          </>
        )}
      </div>
    </Drawer>
  );
}

/**
 * 源码视图：只有哈希核验通过时才按行号切分并高亮；切分基于标准化文本（BOM/CRLF），
 * 与共享契约 §3 的行号口径一致。渲染视图无法可靠定位时，用户从这里得到准确的行范围。
 */
function renderSource(
  content: string,
  location: CitationLocation,
  highlightRef: RefObject<HTMLElement | null>,
) {
  if (location.state !== "verified") {
    return <code>{content}</code>;
  }
  const lines = normalizeSourceText(content).split("\n");
  const before = lines.slice(0, location.startLine - 1).join("\n");
  const range = lines.slice(location.startLine - 1, location.endLine).join("\n");
  const after = lines.slice(location.endLine).join("\n");
  return (
    <code>
      {before && <>{before}{"\n"}</>}
      <mark
        ref={highlightRef}
        className="rounded bg-amber-200/80 px-0.5 text-inherit [box-decoration-break:clone]"
      >
        {range}
      </mark>
      {after && <>{"\n"}{after}</>}
    </code>
  );
}
