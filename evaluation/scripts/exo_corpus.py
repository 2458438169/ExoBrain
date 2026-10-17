"""语料快照、manifest 与分节规则。

分节规则（legacy 与 03 共用的事实基准）：
- 以 ATX 标题行（^#{1,6}\\s+）切分，任何级别都开始新章节，与
  MarkdownParserServiceImpl.extractSections 的"遇到任何标题就结束当前章节"一致。
- 每个章节的 span 从标题行到下一个标题行之前（去掉尾部空行），两端包含。
- 章节正文 = span 内除标题行以外的非空行，用 "\\n" 连接。
  等价于 Java 侧把段落节点文本按 "\\n" 拼接（语料中每个段落占一行）。
"""

import os
import re

from exo_common import EvalError, load_json, norm_ws, read_text_normalized, sha256_file, sha256_text

HEADING_RE = re.compile(r"^(#{1,6})\s+(\S.*?)\s*$")
FENCE_RE = re.compile(r"^\s*(```|~~~)")

MANIFEST_REQUIRED = ("corpusVersion", "createdFromCommit", "documents")
DOCUMENT_REQUIRED = ("docKey", "path", "sha256", "sourcePath", "license", "role")


def load_manifest(path):
    manifest = load_json(path)
    for field in MANIFEST_REQUIRED:
        if field not in manifest:
            raise EvalError("语料 manifest 缺少字段 %s: %s" % (field, path))
    if not manifest["documents"]:
        raise EvalError("语料 manifest 没有任何文档: %s" % path)
    seen = set()
    for document in manifest["documents"]:
        for field in DOCUMENT_REQUIRED:
            if field not in document:
                raise EvalError("manifest 文档缺少字段 %s: %r" % (field, document.get("docKey")))
        key = document["docKey"]
        if key in seen:
            raise EvalError("manifest 文档 key 重复: %s" % key)
        seen.add(key)
        if document["role"] not in ("public-demo", "synthetic-eval-only"):
            raise EvalError("未知文档 role %r（docKey=%s）" % (document["role"], key))
    return manifest


class CorpusSnapshot(object):
    """冻结语料的只读视图：负责 SHA-256 校验与行切分。"""

    def __init__(self, manifest_path, verify_hashes=True):
        self.manifest_path = os.path.abspath(manifest_path)
        self.manifest = load_manifest(self.manifest_path)
        self.version = self.manifest["corpusVersion"]
        self.root = os.path.dirname(self.manifest_path)
        self.manifest_sha256 = sha256_file(self.manifest_path)
        self.documents = list(self.manifest["documents"])
        self._by_key = {}
        for document in self.documents:
            self._by_key[document["docKey"]] = document
        self._texts = {}
        self._lines = {}
        if verify_hashes:
            self.verify()

    # -- 校验 ---------------------------------------------------------------
    def verify(self):
        problems = []
        for document in self.documents:
            path = os.path.join(self.root, document["path"])
            if not os.path.isfile(path):
                problems.append("语料文件不存在: %s (%s)" % (document["path"], document["docKey"]))
                continue
            actual = sha256_file(path)
            if actual != document["sha256"]:
                problems.append(
                    "语料 hash 漂移 %s: manifest=%s actual=%s"
                    % (document["docKey"], document["sha256"], actual)
                )
        if problems:
            raise EvalError("语料快照校验失败:\n- " + "\n- ".join(problems))
        return True

    # -- 读取 ---------------------------------------------------------------
    def doc_keys(self):
        return [document["docKey"] for document in self.documents]

    def doc(self, doc_key):
        if doc_key not in self._by_key:
            raise EvalError("语料中不存在 docKey=%s（可用: %s）" % (doc_key, ", ".join(self.doc_keys())))
        return self._by_key[doc_key]

    def text(self, doc_key):
        if doc_key not in self._texts:
            document = self.doc(doc_key)
            path = os.path.join(self.root, document["path"])
            text = read_text_normalized(path)
            expected = document.get("normalizedSha256")
            if expected and sha256_text(text) != expected:
                raise EvalError(
                    "标准化文本 hash 与 manifest 不一致: %s（BOM/换行处理可能变化）" % doc_key
                )
            self._texts[doc_key] = text
        return self._texts[doc_key]

    def lines(self, doc_key):
        if doc_key not in self._lines:
            self._lines[doc_key] = self.text(doc_key).split("\n")
        return self._lines[doc_key]

    def line_count(self, doc_key):
        return len(self.lines(doc_key))

    def span_text(self, doc_key, start_line, end_line):
        lines = self.lines(doc_key)
        return "\n".join(lines[start_line - 1:end_line])

    def sections(self, doc_key):
        return split_sections(self.lines(doc_key))

    def section_count(self):
        total = 0
        for doc_key in self.doc_keys():
            total += len(self.sections(doc_key))
        return total

    def document_sha256(self):
        return dict((document["docKey"], document["sha256"]) for document in self.documents)


def split_sections(lines):
    """按标题行切分，返回 [{sectionIndex, headingLine, headingText, startLine, endLine, body, bodyLineCount}]。"""
    heading_lines = []
    in_fence = False
    for index, line in enumerate(lines):
        if FENCE_RE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        match = HEADING_RE.match(line)
        if match:
            heading_lines.append((index + 1, match.group(2).strip(), len(match.group(1))))
    sections = []
    for position, item in enumerate(heading_lines):
        heading_line, heading_text, level = item
        if position + 1 < len(heading_lines):
            limit = heading_lines[position + 1][0] - 1
        else:
            limit = len(lines)
        end_line = limit
        while end_line > heading_line and lines[end_line - 1].strip() == "":
            end_line -= 1
        body_lines = []
        for line_no in range(heading_line + 1, end_line + 1):
            if lines[line_no - 1].strip() != "":
                body_lines.append(lines[line_no - 1])
        sections.append(
            {
                "sectionIndex": position,
                "headingLevel": level,
                "headingLine": heading_line,
                "headingText": heading_text,
                "startLine": heading_line,
                "endLine": end_line,
                "body": "\n".join(body_lines),
                "bodyLineCount": len(body_lines),
            }
        )
    return sections


def section_table(snapshot):
    """用于 indexRevision 的稳定表：语料版本 + 每篇文档的章节边界 + 源 hash。"""
    table = []
    for document in snapshot.documents:
        for section in snapshot.sections(document["docKey"]):
            table.append(
                {
                    "docKey": document["docKey"],
                    "sourceHash": document["sha256"],
                    "startLine": section["startLine"],
                    "endLine": section["endLine"],
                    "headingText": section["headingText"],
                }
            )
    return table


def fact_in_span(snapshot, doc_key, start_line, end_line, fact):
    """事实字符串是否出现在证据行范围内（空白折叠后比对）。"""
    span = norm_ws(snapshot.span_text(doc_key, start_line, end_line))
    return norm_ws(fact) in span
