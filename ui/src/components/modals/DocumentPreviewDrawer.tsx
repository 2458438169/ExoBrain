import { useEffect, useState } from "react";
import { Button, Drawer, Empty, Radio, Result, Spin, Typography } from "antd";
import { CodeOutlined, FileTextOutlined, ReloadOutlined } from "@ant-design/icons";
import XMarkdown, { type ComponentProps, type XMarkdownProps } from "@ant-design/x-markdown";
import { getDocumentContent, type DocumentVO } from "../../api/api.ts";
import "./DocumentPreviewDrawer.css";

interface DocumentPreviewDrawerProps {
  document: DocumentVO;
  onClose: () => void;
}

type PreviewResult = {
  documentId: string;
  attempt: number;
} & (
  | { status: "ready"; filename: string; content: string }
  | { status: "error"; message: string }
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

export default function DocumentPreviewDrawer({ document, onClose }: DocumentPreviewDrawerProps) {
  const [mode, setMode] = useState<"preview" | "source">("preview");
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<PreviewResult | null>(null);
  const current = result?.documentId === document.id && result.attempt === attempt ? result : null;

  useEffect(() => {
    const controller = new AbortController();
    void getDocumentContent(document.id, controller.signal)
      .then((response) => {
        if (controller.signal.aborted) return;
        if (response.documentId !== document.id || typeof response.content !== "string" ||
          typeof response.filename !== "string") {
          throw new Error("文档内容无效");
        }
        setResult({
          documentId: document.id,
          attempt,
          status: "ready",
          filename: response.filename,
          content: response.content,
        });
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setResult({
            documentId: document.id,
            attempt,
            status: "error",
            message: error instanceof Error && !(error instanceof TypeError) && error.message
              ? error.message
              : "暂时无法加载这份文档，请重试。",
          });
        }
      });
    return () => controller.abort();
  }, [document.id, attempt]);

  return (
    <Drawer
      open
      onClose={onClose}
      placement="right"
      size={960}
      rootClassName="document-preview-drawer"
      title={
        <div className="min-w-0">
          <div className="mb-1 text-xs font-normal text-slate-500">文档预览</div>
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
            title="文档加载失败"
            subTitle={current.message}
            extra={<Button icon={<ReloadOutlined />} onClick={() => setAttempt((value) => value + 1)}>重试</Button>}
          />
        ) : current.content.length === 0 ? (
          <div className="document-preview-status"><Empty description="这份文档暂无内容" /></div>
        ) : mode === "source" ? (
          <pre className="document-preview-source" tabIndex={0} aria-label="Markdown 源码">
            <code>{current.content}</code>
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
      </div>
    </Drawer>
  );
}
