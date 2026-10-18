"""运行时依赖：Embedding HTTP 客户端、pgvector 访问桥、检索策略适配器。

设计要点：
- 只用标准库（urllib），不依赖 requests/psycopg2；CI 无第三方包也能跑离线部分。
- 密钥只从环境变量读取，不落盘、不进命令行；日志里不出现 Authorization 头。
- `legacy-title-v1` 忠实复现基线语义：章节标题 → Embedding → 1024 维 → KB 过滤 →
  按 `<->`（L2）升序 → LIMIT 3，与 ChunkBgeM3Mapper.xml 的 similaritySearch 一致。
- `body-v1` 是给任务 03 的接入点：只消费已发布的索引，不自行实现正文切块。
"""

import json
import os
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid

from exo_common import (
    EvalError,
    canonical_json,
    norm_ws,
    sha256_text,
    short_hash,
    sql_literal,
    vector_literal,
)
from exo_corpus import section_table

EVAL_KB_NAMESPACE = "https://example.invalid/exobrain-eval"
# urllib 默认的 User-Agent（Python-urllib/3.x）会被 emrank.tech 前面的 Cloudflare 以
# HTTP 403 / "error code: 1010" 拒绝（2026-10-17 实测），因此显式声明一个可识别的客户端标识。
EMBEDDING_USER_AGENT = "ExoBrain-Eval/0.1 (+https://github.com/2458438169/ExoBrain)"


class ServiceUnavailable(EvalError):
    """外部服务不可用（Embedding / 数据库），不能当作"资料不足"。"""


# ---------------------------------------------------------------------------
# Embedding 客户端
# ---------------------------------------------------------------------------

class EmbeddingClient(object):
    """POST {baseUrl}/embeddings，model 固定为配置值（本任务为 BAAI/bge-m3）。"""

    def __init__(self, base_url, model, api_key, timeout=60.0, max_retries=3,
                 backoff_seconds=1.0, expected_dimension=1024):
        if not api_key:
            raise ServiceUnavailable(
                "Embedding API Key 缺失：请把密钥放入环境变量（配置里的 apiKeyEnv），"
                "不要写进命令行或提交的配置文件"
            )
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.api_key = api_key
        self.timeout = float(timeout)
        self.max_retries = int(max_retries)
        self.backoff_seconds = float(backoff_seconds)
        self.expected_dimension = int(expected_dimension)
        self.records = []
        self.call_count = 0
        self.retry_count = 0
        self.input_count = 0
        self.total_tokens = 0

    def _post(self, texts):
        payload = json.dumps({"model": self.model, "input": list(texts)}, ensure_ascii=False)
        request = urllib.request.Request(
            self.base_url + "/embeddings",
            data=payload.encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer " + self.api_key,
                "User-Agent": EMBEDDING_USER_AGENT,
            },
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=self.timeout) as response:
            return json.loads(response.read().decode("utf-8"))

    def embed(self, texts):
        if isinstance(texts, str):
            texts = [texts]
        texts = list(texts)
        attempts = 0
        last_error = None
        started = time.time()
        while attempts <= self.max_retries:
            attempts += 1
            try:
                body = self._post(texts)
                vectors = [item["embedding"] for item in body.get("data", [])]
                if len(vectors) != len(texts):
                    raise ServiceUnavailable(
                        "Embedding 返回数量不符：请求 %d 条，返回 %d 条" % (len(texts), len(vectors))
                    )
                for vector in vectors:
                    if len(vector) != self.expected_dimension:
                        raise ServiceUnavailable(
                            "Embedding 维度不符：期望 %d，实际 %d（模型 %s）"
                            % (self.expected_dimension, len(vector), self.model)
                        )
                latency_ms = (time.time() - started) * 1000.0
                usage = body.get("usage") or {}
                self.total_tokens += int(usage.get("total_tokens") or 0)
                self.call_count += 1
                self.retry_count += attempts - 1
                self.input_count += len(texts)
                record = {
                    "type": "embedding",
                    "model": body.get("model") or self.model,
                    "inputCount": len(texts),
                    "attempts": attempts,
                    "latencyMs": round(latency_ms, 3),
                    "promptTokens": usage.get("prompt_tokens"),
                    "totalTokens": usage.get("total_tokens"),
                }
                self.records.append(record)
                return {
                    "vectors": vectors,
                    "latencyMs": latency_ms,
                    "attempts": attempts,
                    "record": record,
                }
            except urllib.error.HTTPError as exc:
                status = exc.code
                detail = ""
                try:
                    detail = exc.read().decode("utf-8", "replace")[:300]
                except Exception:
                    detail = ""
                last_error = "HTTP %s: %s" % (status, detail)
                if status in (400, 401, 403, 404):
                    self.records.append({
                        "type": "embedding",
                        "model": self.model,
                        "inputCount": len(texts),
                        "attempts": attempts,
                        "status": status,
                        "error": last_error,
                    })
                    raise ServiceUnavailable("Embedding 请求被拒绝（%s）: %s" % (status, last_error))
            except (urllib.error.URLError, socket.timeout, ValueError) as exc:
                last_error = "%s: %s" % (type(exc).__name__, exc)
            if attempts <= self.max_retries:
                time.sleep(self.backoff_seconds * attempts)
        self.records.append({
            "type": "embedding",
            "model": self.model,
            "inputCount": len(texts),
            "attempts": attempts,
            "status": "failed",
            "error": last_error,
        })
        raise ServiceUnavailable("Embedding 连续失败 %d 次: %s" % (attempts, last_error))

    def describe(self):
        return {
            "model": self.model,
            "baseUrl": self.base_url,
            "expectedDimension": self.expected_dimension,
            "timeoutSeconds": self.timeout,
            "maxRetries": self.max_retries,
            "calls": self.call_count,
            "retries": self.retry_count,
            "inputs": self.input_count,
            "totalTokens": self.total_tokens,
        }


# ---------------------------------------------------------------------------
# pgvector 访问桥
# ---------------------------------------------------------------------------

class PsqlBridge(object):
    """通过 `docker exec psql` 访问真实 PostgreSQL。

    本机 Anaconda 环境没有 psycopg2，因此使用 psql 的 stdin/stdout 通道：
    SQL 以 UTF-8 字节写入子进程 stdin，结果用 `-A -t --field-separator=tab` 输出。
    """

    def __init__(self, container="exobrain-dev-pg", user="exobrain", database="exobrain_eval",
                 docker="docker", timeout=180):
        self.container = container
        self.user = user
        self.database = database
        self.docker = docker
        self.timeout = float(timeout)

    def _base_command(self):
        return [
            self.docker, "exec", "-i",
            "-e", "PGCLIENTENCODING=UTF8",
            self.container,
            "psql", "-X", "-q", "-A", "-t", "--no-psqlrc",
            "--field-separator=\t",
            "-U", self.user, "-d", self.database,
            "-v", "ON_ERROR_STOP=1",
        ]

    def run_sql(self, sql):
        command = self._base_command()
        try:
            process = subprocess.run(
                command,
                input=sql.encode("utf-8"),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=self.timeout,
            )
        except subprocess.TimeoutExpired:
            raise ServiceUnavailable("psql 执行超时（%ss）" % self.timeout)
        except OSError as exc:
            raise ServiceUnavailable("无法启动 docker/psql: %s" % exc)
        stderr = process.stderr.decode("utf-8", "replace").strip()
        if process.returncode != 0:
            raise ServiceUnavailable("SQL 执行失败（rc=%s）: %s" % (process.returncode, stderr[:800]))
        return process.stdout.decode("utf-8", "replace")

    def query(self, sql):
        rows = []
        for line in self.run_sql(sql).split("\n"):
            if line == "":
                continue
            rows.append(line.split("\t"))
        return rows

    def execute(self, sql):
        self.run_sql(sql)
        return True

    def scalar(self, sql):
        rows = self.query(sql)
        if not rows or not rows[0]:
            return None
        return rows[0][0]

    def describe(self):
        return {
            "transport": "docker-exec+psql",
            "container": self.container,
            "database": self.database,
            "user": self.user,
        }


def make_bridge_from_config(config):
    pg = (config.get("pgvector") or {})
    container = pg.get("container") or os.environ.get("EXOBRAIN_EVAL_PG_CONTAINER") or "exobrain-dev-pg"
    return PsqlBridge(
        container=container,
        user=pg.get("user") or "exobrain",
        database=pg.get("database") or "exobrain_eval",
        docker=pg.get("docker") or "docker",
        timeout=pg.get("timeoutSeconds") or 180,
    )


# ---------------------------------------------------------------------------
# 策略适配器
# ---------------------------------------------------------------------------

def deterministic_uuid(kind, value):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "%s:%s:%s" % (EVAL_KB_NAMESPACE, kind, value)))


PRODUCTION_SQL_TEMPLATE = (
    "SELECT id,\n"
    "       kb_id,\n"
    "       doc_id,\n"
    "       content,\n"
    "       metadata,\n"
    "       embedding,\n"
    "       created_at,\n"
    "       updated_at\n"
    "FROM chunk_bge_m3\n"
    "WHERE kb_id = CAST('%s' AS uuid)\n"
    "ORDER BY embedding <-> '%s'::vector\n"
    "LIMIT %d\n"
)


def decode_json_column(text):
    """解码 SQL 里 `to_json(column)::text` 的结果。

    chunk_bge_m3.content / metadata 的列类型是 TEXT，psql 端 `to_json(text)` 会得到
    一个 JSON 字符串（双重编码），json.loads 一次只得到原始 JSON 文本；
    这里再解一次，避免 metadata 退化成 {} 导致 docKey/行号全部为 None（真实运行中踩到过）。
    """
    if not text:
        return None
    try:
        value = json.loads(text)
    except ValueError:
        return None
    if isinstance(value, str):
        try:
            nested = json.loads(value)
        except ValueError:
            return value
        return nested
    return value


def document_keys_by_name(snapshot):
    """把冻结语料里的文件名映射到 docKey。

    body-v1 的产品元数据只写 `documentName`（上传时的原始文件名），它没有评测专用的
    `docKey` 概念（`docs/development/contracts.md` 第 3 节的 docKey 是 02 的标注键）。
    这里按 manifest 里 `documents[].path` 的 basename 建立映射：同一文件名只允许对应
    一个 docKey，出现重名直接失败，绝不猜测或用相似度兜底。
    """
    mapping = {}
    for document in snapshot.documents:
        name = os.path.basename(document["path"])
        existing = mapping.get(name)
        if existing is not None and existing != document["docKey"]:
            raise EvalError(
                "冻结语料里文件名重复（%s），无法把 documentName 唯一映射到 docKey" % name
            )
        mapping[name] = document["docKey"]
    return mapping


def hit_from_row(row, rank, index_version, index_revision):
    """把 SQL 行转成可评分的 hit。

    行格式：id, doc_id, to_json(content)::text, to_json(metadata)::text, distance::text
    """
    chunk_id, doc_id, content_json, metadata_json, distance = row[:5]
    content = decode_json_column(content_json)
    if not isinstance(content, str):
        content = "" if content is None else json.dumps(content, ensure_ascii=False)
    metadata = decode_json_column(metadata_json)
    if not isinstance(metadata, dict):
        metadata = {}
    return {
        "rank": rank,
        "chunkId": chunk_id,
        "docId": doc_id,
        "docKey": metadata.get("docKey"),
        "documentName": metadata.get("documentName"),
        "sourceHash": metadata.get("sourceHash"),
        "headingPath": metadata.get("headingPath") or [],
        "startLine": metadata.get("startLine"),
        "endLine": metadata.get("endLine"),
        "distance": float(distance) if distance not in (None, "") else None,
        "indexVersion": metadata.get("indexVersion") or index_version,
        "indexRevision": metadata.get("indexRevision") or index_revision,
        "payloadSha256": sha256_text(content),
        "payloadLength": len(content),
        "payload": content,
    }


class LegacyTitleV1Strategy(object):
    """基线策略：章节标题作为 Embedding 输入，KB 过滤 + L2 排序 + top3，返回章节正文。"""

    name = "legacy-title-v1"
    index_version = "legacy-title-v1"
    trust = "graduated"
    payload_source = "chunk_bge_m3.content（章节正文，与 RagServiceImpl.similaritySearch 返回的字段一致）"

    def __init__(self, snapshot, bridge, embedder, top_k=3, kb_id=None, query_sql_override=None):
        self.snapshot = snapshot
        self.bridge = bridge
        self.embedder = embedder
        self.top_k = int(top_k)
        self.kb_id = kb_id or deterministic_uuid("kb", "%s:%s" % (self.index_version, snapshot.version))
        self.section_table = section_table(snapshot)
        self.index_revision = "%s/%s/%s" % (
            self.index_version,
            snapshot.version,
            short_hash(canonical_json(self.section_table)),
        )
        self.query_sql_override = query_sql_override
        self.build_stats = None

    # -- 建索引 -------------------------------------------------------------
    def build_sections(self):
        sections = []
        for document in self.snapshot.documents:
            lines = self.snapshot.lines(document["docKey"])
            for section in self.snapshot.sections(document["docKey"]):
                sections.append({
                    "docKey": document["docKey"],
                    "docId": deterministic_uuid("doc", document["docKey"]),
                    "sourceHash": document["sha256"],
                    "filename": os.path.basename(document["path"]),
                    "role": document["role"],
                    "headingText": section["headingText"],
                    "headingPath": [section["headingText"]],
                    "startLine": section["startLine"],
                    "endLine": section["endLine"],
                    "body": section["body"],
                    "lineCount": len(lines),
                })
        return sections

    def build_index(self):
        """在隔离目标库里重建 legacy 索引：只把 manifest 列出的原始文档章节写入。"""
        sections = self.build_sections()
        embed_results = []
        for section in sections:
            result = self.embedder.embed(section["headingText"])
            section["vector"] = result["vectors"][0]
            embed_results.append(result["latencyMs"])

        statements = ["SET standard_conforming_strings = on;", "SET client_encoding TO 'UTF8';"]
        statements.append(
            "DELETE FROM chunk_bge_m3 WHERE kb_id = CAST(%s AS uuid);" % sql_literal(self.kb_id)
        )
        statements.append(
            "INSERT INTO knowledge_base (id, name, description, metadata, created_at, updated_at)\n"
            "VALUES (CAST(%s AS uuid), %s, %s, CAST(%s AS jsonb), NOW(), NOW())\n"
            "ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description,"
            " metadata = EXCLUDED.metadata, updated_at = NOW();" % (
                sql_literal(self.kb_id),
                sql_literal("ExoBrain 检索评测（任务 02）：%s / %s" % (self.index_version, self.snapshot.version)),
                sql_literal("评测专用知识库，内容来自 evaluation/corpus/%s 的冻结语料。" % self.snapshot.version),
                sql_literal(json.dumps({"exoEval": True, "purpose": "retrieval-evaluation-02"})),
            )
        )
        self.bridge.execute("\n".join(statements))

        document_statements = []
        for document in self.snapshot.documents:
            doc_id = deterministic_uuid("doc", document["docKey"])
            path = os.path.join(self.snapshot.root, document["path"])
            size = os.path.getsize(path)
            document_statements.append(
                "INSERT INTO document (id, kb_id, filename, filetype, size, metadata, created_at, updated_at)\n"
                "VALUES (CAST(%s AS uuid), CAST(%s AS uuid), %s, %s, %d, CAST(%s AS jsonb), NOW(), NOW())\n"
                "ON CONFLICT (id) DO UPDATE SET filename = EXCLUDED.filename, metadata = EXCLUDED.metadata,"
                " updated_at = NOW();" % (
                    sql_literal(doc_id),
                    sql_literal(self.kb_id),
                    sql_literal(os.path.basename(document["path"])),
                    sql_literal("md"),
                    size,
                    sql_literal(json.dumps({
                        "exoEval": True,
                        "docKey": document["docKey"],
                        "sourceHash": document["sha256"],
                        "role": document["role"],
                    })),
                )
            )
        self.bridge.execute("\n".join(document_statements))

        insert_statements = []
        for section in sections:
            metadata = {
                "exoEval": True,
                "indexVersion": self.index_version,
                "indexRevision": self.index_revision,
                "docKey": section["docKey"],
                "sourceHash": section["sourceHash"],
                "headingPath": section["headingPath"],
                "startLine": section["startLine"],
                "endLine": section["endLine"],
                "corpusVersion": self.snapshot.version,
                "role": section["role"],
                "note": "eval-only metadata：向量只用于检索，来源信息用于评测核验",
            }
            insert_statements.append(
                "INSERT INTO chunk_bge_m3 (kb_id, doc_id, content, metadata, embedding, created_at, updated_at)\n"
                "VALUES (CAST(%s AS uuid), CAST(%s AS uuid), %s, %s, %s::vector, NOW(), NOW());" % (
                    sql_literal(self.kb_id),
                    sql_literal(section["docId"]),
                    sql_literal(section["body"]),
                    sql_literal(json.dumps(metadata, ensure_ascii=False)),
                    sql_literal(vector_literal(section["vector"])),
                )
            )
        self.bridge.execute("\n".join(insert_statements))

        row_count = int(self.bridge.scalar(
            "SELECT count(*) FROM chunk_bge_m3 WHERE kb_id = CAST(%s AS uuid);" % sql_literal(self.kb_id)
        ) or 0)
        empty_titles = len([s for s in sections if not norm_ws(s["headingText"])])
        self.build_stats = {
            "variant": self.name,
            "indexVersion": self.index_version,
            "indexRevision": self.index_revision,
            "kbId": self.kb_id,
            "sections": len(sections),
            "indexedRows": row_count,
            "emptyTitlesSkipped": empty_titles,
            "embeddingInputs": len(sections),
            "embeddingInputText": "章节标题（不含正文、不含文档名）",
            "embeddingLatencyMs": {
                "n": len(embed_results),
                "min": min(embed_results) if embed_results else None,
                "max": max(embed_results) if embed_results else None,
                "total": sum(embed_results) if embed_results else None,
            },
            "sectionsPerDocument": self._section_counts(),
        }
        return self.build_stats

    def _section_counts(self):
        counts = []
        for document in self.snapshot.documents:
            counts.append({
                "docKey": document["docKey"],
                "role": document["role"],
                "sections": len(self.snapshot.sections(document["docKey"])),
                "lines": self.snapshot.line_count(document["docKey"]),
            })
        return counts

    def index_row_count(self):
        return int(self.bridge.scalar(
            "SELECT count(*) FROM chunk_bge_m3 WHERE kb_id = CAST(%s AS uuid);" % sql_literal(self.kb_id)
        ) or 0)

    # -- 检索 ---------------------------------------------------------------
    def build_query_sql(self, query_vector_text):
        if self.query_sql_override:
            return self.query_sql_override
        return (
            "SELECT id,\n"
            "       doc_id,\n"
            "       to_json(content)::text AS content_json,\n"
            "       to_json(metadata)::text AS metadata_json,\n"
            "       (embedding <-> '%s'::vector)::text AS distance\n"
            "FROM chunk_bge_m3\n"
            "WHERE kb_id = CAST('%s' AS uuid)\n"
            "ORDER BY embedding <-> '%s'::vector\n"
            "LIMIT %d;"
        ) % (query_vector_text, self.kb_id, query_vector_text, self.top_k)

    def plan_diagnostic_sql(self, top_k=None):
        """同一条查询在强制顺序扫描下的候选集合：用于检查 ivfflat 索引是否少返回结果。"""
        top_k = int(top_k or self.top_k)
        literal = getattr(self, "last_query_vector_text", None)
        if not literal:
            return None
        return (
            "SET enable_indexscan = off;\n"
            "SET enable_bitmapscan = off;\n"
            "SELECT id, to_json(metadata)::text\n"
            "FROM chunk_bge_m3\n"
            "WHERE kb_id = CAST('%s' AS uuid)\n"
            "ORDER BY embedding <-> '%s'::vector\n"
            "LIMIT %d;"
        ) % (self.kb_id, literal, top_k)

    def explain_sql(self, top_k=None):
        """生产 SQL 的执行计划（不 ANALYZE，避免把计划采集算进延迟样本）。"""
        top_k = int(top_k or self.top_k)
        literal = getattr(self, "last_query_vector_text", None)
        if not literal:
            return None
        return (
            "EXPLAIN (COSTS OFF)\n"
            "SELECT id, kb_id, doc_id, content, metadata, embedding, created_at, updated_at\n"
            "FROM chunk_bge_m3\n"
            "WHERE kb_id = CAST('%s' AS uuid)\n"
            "ORDER BY embedding <-> '%s'::vector\n"
            "LIMIT %d;"
        ) % (self.kb_id, literal, top_k)

    def search(self, question, top_k=None):
        top_k = int(top_k or self.top_k)
        started = time.time()
        embedding = self.embedder.embed(question)
        embedded_at = time.time()
        self.last_query_vector_text = vector_literal(embedding["vectors"][0])
        sql = self.build_query_sql(self.last_query_vector_text)
        if top_k != self.top_k:
            sql = sql.replace("LIMIT %d;" % self.top_k, "LIMIT %d;" % top_k)
        try:
            rows = self.bridge.query(sql)
        except ServiceUnavailable as exc:
            return {
                "status": "UNAVAILABLE",
                "errorCode": "DB_UNAVAILABLE",
                "message": str(exc),
                "hits": [],
                "embeddingLatencyMs": embedding["latencyMs"],
                "retrievalLatencyMs": (time.time() - started) * 1000.0,
                "sql": sql,
            }
        hits = []
        for index, row in enumerate(rows, start=1):
            hits.append(hit_from_row(row, index, self.index_version, self.index_revision))
        return {
            "status": "OK" if hits else "NO_MATCH",
            "errorCode": None,
            "hits": hits,
            "embeddingLatencyMs": embedding["latencyMs"],
            "retrievalLatencyMs": (time.time() - started) * 1000.0,
            "sql": sql,
            "embeddedAt": embedded_at - started,
        }

    def describe(self):
        return {
            "variant": self.name,
            "indexVersion": self.index_version,
            "indexRevision": self.index_revision,
            "kbId": self.kb_id,
            "topK": self.top_k,
            "embeddingInput": "章节标题文本（title）",
            "payload": self.payload_source,
            "kbFilter": "WHERE kb_id = CAST(<eval kb uuid> AS uuid)（与 mapper 的 CAST(#{kbId} AS uuid) 一致）",
            "ordering": "ORDER BY embedding <-> :query LIMIT k（L2，越小越近）",
            "productionMapper": "jchatmind/src/main/resources/mapper/ChunkBgeM3Mapper.xml similaritySearch",
            "knownDifferenceFromProduction": (
                "SELECT 列表额外返回 distance 与 to_json(metadata)，用于离线评分；"
                "WHERE / ORDER BY / LIMIT 与生产 SQL 相同，未加入阈值、重排或正文向量。"
            ),
            "sqlTemplate": PRODUCTION_SQL_TEMPLATE,
        }


class BodyV1Strategy(object):
    """任务 03 的接入点：只消费已发布的 body-v1 索引，不实现正文切块。

    产品检索的 WHERE 是「KB + 当前 PUBLISHED revision」（`RetrievalIndexRepository`），
    因此 `use_active_revision=True` 时本适配器按 `exobrain_kb_index_state.active_revision_id`
    过滤；否则同一 KB 里被新版本替换的旧 chunk 也会进 top-k，指标就不是产品行为了。

    产品元数据写的是 `documentName`（原始文件名）而不是评测专用的 `docKey`，
    `doc_key_by_name` 用冻结 manifest 的文件名映射补齐；映射缺失时该 hit 标记为
    `scorable=False`，不会静默当成命中。
    """

    name = "body-v1"
    index_version = "body-v1"

    def __init__(self, snapshot, bridge, embedder, top_k=3, kb_id=None, metadata_like=None,
                 use_active_revision=False, doc_key_by_name=None):
        self.snapshot = snapshot
        self.bridge = bridge
        self.embedder = embedder
        self.top_k = int(top_k)
        self.kb_id = kb_id or deterministic_uuid("kb", "%s:%s" % (self.index_version, snapshot.version))
        self.metadata_like = metadata_like
        self.use_active_revision = bool(use_active_revision)
        self.doc_key_by_name = doc_key_by_name or {}

    def _where(self):
        where = "kb_id = CAST('%s' AS uuid)" % self.kb_id
        if self.use_active_revision:
            where += (
                " AND revision_id = (SELECT active_revision_id FROM exobrain_kb_index_state"
                " WHERE kb_id = CAST('%s' AS uuid))" % self.kb_id
            )
        if self.metadata_like:
            where += " AND metadata LIKE %s" % sql_literal(self.metadata_like)
        return where

    def index_row_count(self):
        return int(self.bridge.scalar("SELECT count(*) FROM chunk_bge_m3 WHERE %s;" % self._where()) or 0)

    def search(self, question, top_k=None):
        top_k = int(top_k or self.top_k)
        try:
            row_count = self.index_row_count()
        except ServiceUnavailable as exc:
            return {"status": "UNAVAILABLE", "errorCode": "DB_UNAVAILABLE", "message": str(exc), "hits": []}
        if row_count == 0:
            return {
                "status": "UNAVAILABLE",
                "errorCode": "INDEX_NOT_FOUND",
                "message": (
                    "kb_id=%s 没有 body-v1 索引；该索引由任务 03 构建（chunk_bge_m3.metadata 至少包含 "
                    "indexVersion/documentName/sourceHash/startLine/endLine/headingPath）。" % self.kb_id
                ),
                "hits": [],
            }
        started = time.time()
        embedding = self.embedder.embed(question)
        sql = (
            "SELECT id, doc_id, to_json(content)::text, to_json(metadata)::text,\n"
            "       (embedding <-> '%s'::vector)::text\n"
            "FROM chunk_bge_m3\n"
            "WHERE %s\n"
            "ORDER BY embedding <-> '%s'::vector\n"
            "LIMIT %d;"
        ) % (vector_literal(embedding["vectors"][0]), self._where(),
             vector_literal(embedding["vectors"][0]), top_k)
        try:
            rows = self.bridge.query(sql)
        except ServiceUnavailable as exc:
            return {
                "status": "UNAVAILABLE", "errorCode": "DB_UNAVAILABLE", "message": str(exc), "hits": [],
                "embeddingLatencyMs": embedding["latencyMs"],
                "retrievalLatencyMs": (time.time() - started) * 1000.0,
            }
        hits = [hit_from_row(row, index, self.index_version, None) for index, row in enumerate(rows, start=1)]
        for hit in hits:
            if not hit.get("docKey") and hit.get("documentName"):
                hit["docKey"] = self.doc_key_by_name.get(hit["documentName"])
            if hit.get("startLine") is None or hit.get("endLine") is None or not hit.get("docKey"):
                hit["scorable"] = False
        return {
            "status": "OK" if hits else "NO_MATCH",
            "errorCode": None,
            "hits": hits,
            "embeddingLatencyMs": embedding["latencyMs"],
            "retrievalLatencyMs": (time.time() - started) * 1000.0,
            "sql": sql,
        }

    def describe(self):
        return {
            "variant": self.name,
            "kbId": self.kb_id,
            "topK": self.top_k,
            "note": "任务 03 接入点：02 只查询已发布的 body-v1 索引，不生成切块。",
            "requiredMetadataKeys": ["indexVersion", "documentName", "sourceHash", "startLine", "endLine", "headingPath"],
            "metadataLike": self.metadata_like,
            "useActiveRevision": self.use_active_revision,
            "docKeyResolution": (
                "manifest-document-name" if self.doc_key_by_name else None
            ),
            "docKeyResolutionNote": (
                "产品元数据不含评测键 docKey；按冻结 manifest 的原始文件名唯一映射。"
                if self.doc_key_by_name else None
            ),
        }


def make_strategy(name, snapshot, bridge, embedder, config):
    variants = (config.get("variants") or {})
    settings = variants.get(name) or {}
    top_k = settings.get("topK") or (config.get("run") or {}).get("topK") or 3
    if name == LegacyTitleV1Strategy.name:
        return LegacyTitleV1Strategy(
            snapshot, bridge, embedder, top_k=top_k, kb_id=settings.get("kbId")
        )
    if name == BodyV1Strategy.name:
        doc_key_by_name = None
        if settings.get("docKeyFromDocumentName"):
            doc_key_by_name = document_keys_by_name(snapshot)
        return BodyV1Strategy(
            snapshot, bridge, embedder, top_k=top_k, kb_id=settings.get("kbId"),
            metadata_like=settings.get("metadataLike"),
            use_active_revision=settings.get("useActiveRevision", False),
            doc_key_by_name=doc_key_by_name,
        )
    raise EvalError(
        "未知 variant=%s（当前实现：%s；body-v1 等候选策略由对应任务提供适配器）"
        % (name, ", ".join([LegacyTitleV1Strategy.name, BodyV1Strategy.name]))
    )
