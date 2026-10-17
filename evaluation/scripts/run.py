"""真实检索运行器：在隔离目标库建索引，逐题调用真实 Embedding 与真实 pgvector。

用法（在仓库根目录执行）：
  python evaluation/scripts/run.py --config <仓库外配置.json> \
      --dataset evaluation/datasets/questions-v1.jsonl \
      --variant legacy-title-v1 --out <结果目录> [--build-index] [--limit N]

退出码：0 正常完成；2 题集校验失败；3 配置/环境错误；4 全部题目服务故障（未取得真实结果）。
密钥只从环境变量读取（配置里的 apiKeyEnv），不写入命令行、配置或结果文件。
"""

import argparse
import os
import platform
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from exo_common import (
    EvalError,
    ensure_dir,
    load_json,
    print_json,
    relpath,
    round_floats,
    sha256_file,
    utc_now_iso,
    write_json,
    write_jsonl,
)
from exo_corpus import CorpusSnapshot, section_table
from exo_dataset import dataset_stats, load_questions, test_split_fingerprint, validate_questions
from exo_runtime import EmbeddingClient, ServiceUnavailable, make_bridge_from_config, make_strategy
from exo_scoring import build_summary

DEFAULT_MANIFEST = os.path.join("evaluation", "corpus", "v1-2026-10-14", "manifest.json")
DEFAULT_DATASET = os.path.join("evaluation", "datasets", "questions-v1.jsonl")


def parse_args(argv):
    parser = argparse.ArgumentParser(description="ExoBrain 检索评测：真实 Embedding + 真实 pgvector")
    parser.add_argument("--config", required=True, help="仓库外配置 JSON（不含密钥）")
    parser.add_argument("--dataset", default=DEFAULT_DATASET, help="题集 JSONL")
    parser.add_argument("--variant", default="legacy-title-v1", help="检索策略标识，例如 legacy-title-v1")
    parser.add_argument("--out", required=True, help="结果目录")
    parser.add_argument("--corpus-manifest", default=None, help="语料 manifest（默认取配置或内置路径）")
    parser.add_argument("--freeze", default=None, help="冻结记录 JSON（校验题集与测试集哈希）")
    parser.add_argument("--build-index", action="store_true", help="重建该策略的索引")
    parser.add_argument("--limit", type=int, default=None, help="只跑前 N 题（调试用，正式结果不要使用）")
    parser.add_argument("--no-plan-diagnostic", action="store_true", help="跳过顺序扫描对照诊断")
    parser.add_argument("--json", action="store_true", help="额外把汇总 JSON 打到标准输出")
    return parser.parse_args(argv)


def git_info():
    info = {"commit": None, "branch": None, "source": None}
    try:
        commit = subprocess.run(["git", "rev-parse", "HEAD"], stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, timeout=20)
        if commit.returncode == 0:
            info["commit"] = commit.stdout.decode("utf-8", "replace").strip()
        branch = subprocess.run(["git", "rev-parse", "--abbrev-ref", "HEAD"], stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, timeout=20)
        if branch.returncode == 0:
            info["branch"] = branch.stdout.decode("utf-8", "replace").strip()
        info["source"] = "git rev-parse（只读；不执行 git status，避免与并行任务争用索引锁）"
    except (OSError, subprocess.SubprocessError) as exc:
        info["source"] = "git 不可用: %s" % exc
    return info


def environment_info():
    return {
        "pythonVersion": sys.version.split()[0],
        "platform": platform.platform(),
        "processor": platform.processor(),
    }


def load_config(path):
    if not os.path.isfile(path):
        raise EvalError("配置文件不存在: %s" % path)
    config = load_json(path)
    if not isinstance(config, dict):
        raise EvalError("配置文件必须是 JSON 对象: %s" % path)
    text = open(path, "r", encoding="utf-8").read()
    embedding = config.get("embedding") or {}
    for key in ("apiKey", "api_key", "key", "token"):
        if key in embedding:
            raise EvalError("配置里不允许出现密钥字段 %r：请改用 apiKeyEnv 指向环境变量" % key)
    if "Bearer " in text:
        raise EvalError("配置文件中疑似包含密钥，拒绝运行")
    return config


def build_embedder(config):
    embedding = config.get("embedding") or {}
    env_name = embedding.get("apiKeyEnv") or "EXOBRAIN_EVAL_EMBEDDING_API_KEY"
    api_key = os.environ.get(env_name)
    return EmbeddingClient(
        base_url=embedding.get("baseUrl") or "https://emrank.tech/v1",
        model=embedding.get("model") or "BAAI/bge-m3",
        api_key=api_key,
        timeout=embedding.get("timeoutSeconds") or 60,
        max_retries=embedding.get("maxRetries") if embedding.get("maxRetries") is not None else 3,
        backoff_seconds=embedding.get("backoffSeconds") or 1.5,
        expected_dimension=embedding.get("expectedDimension") or 1024,
    ), env_name


def resolve_paths(config, args):
    corpus_cfg = config.get("corpus") or {}
    dataset_cfg = config.get("dataset") or {}
    manifest = args.corpus_manifest or corpus_cfg.get("manifest") or DEFAULT_MANIFEST
    dataset = args.dataset or dataset_cfg.get("path") or DEFAULT_DATASET
    return manifest, dataset


def run_evaluation(config, dataset_path, variant_name, out_dir, manifest_path, build_index=False,
                   limit=None, bridge=None, embedder=None, snapshot=None, questions=None,
                   plan_diagnostic=True, log=None, log_path=None):
    started_iso = utc_now_iso()
    started = time.time()
    ensure_dir(out_dir)
    log_lines = []

    def emit(message):
        line = "[%s] %s" % (time.strftime("%H:%M:%S"), message)
        log_lines.append(line)
        if log is not None:
            log(line)

    if snapshot is None:
        snapshot = CorpusSnapshot(manifest_path)
    snapshot.verify()
    if questions is None:
        questions = load_questions(dataset_path)
    problems = validate_questions(questions, snapshot, dataset_path=os.path.abspath(dataset_path))
    if problems:
        raise EvalError("题集校验失败，拒绝运行:\n- " + "\n- ".join(problems))
    if limit:
        questions = questions[:int(limit)]

    owned_embedder = embedder is None
    if embedder is None:
        embedder, key_env = build_embedder(config)
    else:
        key_env = "injected"
    if bridge is None:
        bridge = make_bridge_from_config(config)
    strategy = make_strategy(variant_name, snapshot, bridge, embedder, config)
    run_cfg = config.get("run") or {}
    top_k = int(getattr(strategy, "top_k", run_cfg.get("topK") or 3))
    k_values = run_cfg.get("kValues") or [1, top_k]

    emit("语料 %s：%d 篇文档，%d 个 section（分节规则见 evaluation/scripts/exo_corpus.py）" % (
        snapshot.version, len(snapshot.documents), snapshot.section_count()))

    index_stats = None
    index_row_count = None
    try:
        index_row_count = strategy.index_row_count()
    except (ServiceUnavailable, EvalError) as exc:
        emit("索引状态查询失败: %s" % exc)
    if build_index or not index_row_count:
        if hasattr(strategy, "build_index"):
            emit("重建 %s 索引（真实 Embedding 逐条生成标题向量）" % variant_name)
            index_stats = strategy.build_index()
            index_row_count = index_stats.get("indexedRows")
            emit("索引完成：%d 行（embedding 输入 %d 条）" % (index_row_count or 0, index_stats.get("embeddingInputs") or 0))
        else:
            emit("variant=%s 没有可用的建索引实现（应由对应任务提供）" % variant_name)
    else:
        emit("复用已存在的 %s 索引：%d 行" % (variant_name, index_row_count))

    warmup = None
    if int(run_cfg.get("warmupQueries") or 0) > 0:
        warm_started = time.time()
        try:
            embedder.embed("评测预热查询：本次调用不计入任何题目的延迟样本")
            warmup = {"queries": 1, "text": "固定预热文本（不属于题集）", "latencyMs": (time.time() - warm_started) * 1000.0}
            emit("预热完成：%.0f ms（该样本不计入延迟统计）" % warmup["latencyMs"])
        except ServiceUnavailable as exc:
            warmup = {"queries": 1, "error": str(exc)}
            emit("预热失败: %s" % exc)

    results_path = os.path.join(out_dir, "results.jsonl")
    if os.path.exists(results_path):
        os.remove(results_path)
    results = []
    explain_text = None
    production_sql = None
    diagnostic_sql = None
    diagnostic_mismatch = 0
    for position, question in enumerate(questions, start=1):
        row = {
            "schemaVersion": 1,
            "id": question.get("id"),
            "split": question.get("split"),
            "groupId": question.get("groupId"),
            "category": question.get("category"),
            "answerable": question.get("answerable"),
            "question": question.get("question"),
            "variant": variant_name,
        }
        try:
            outcome = strategy.search(question.get("question"))
        except ServiceUnavailable as exc:
            outcome = {"status": "UNAVAILABLE", "errorCode": "EMBEDDING_UNAVAILABLE", "message": str(exc), "hits": []}
        except EvalError as exc:
            outcome = {"status": "UNAVAILABLE", "errorCode": "EVAL_ERROR", "message": str(exc), "hits": []}
        row.update({
            "status": outcome.get("status"),
            "errorCode": outcome.get("errorCode"),
            "message": outcome.get("message"),
            "hitCount": len(outcome.get("hits") or []),
            "embeddingLatencyMs": round(outcome.get("embeddingLatencyMs") or 0.0, 3),
            "retrievalLatencyMs": round(outcome.get("retrievalLatencyMs") or 0.0, 3),
            "hits": outcome.get("hits") or [],
        })
        if outcome.get("sql") and production_sql is None:
            production_sql = outcome["sql"]
        if plan_diagnostic and hasattr(strategy, "plan_diagnostic_sql"):
            diag_sql = strategy.plan_diagnostic_sql(top_k)
            if diag_sql:
                if diagnostic_sql is None:
                    diagnostic_sql = diag_sql
                try:
                    rows = bridge.query(diag_sql)
                    seq_ids = [entry[0] for entry in rows]
                    natural_ids = [hit.get("chunkId") for hit in row["hits"]]
                    row["planDiagnostic"] = {
                        "naturalIds": natural_ids,
                        "seqScanIds": seq_ids,
                        "sameIdsInOrder": natural_ids == seq_ids,
                        "naturalRowCount": len(natural_ids),
                        "seqScanRowCount": len(seq_ids),
                    }
                    if natural_ids != seq_ids:
                        diagnostic_mismatch += 1
                except ServiceUnavailable as exc:
                    row["planDiagnostic"] = {"error": str(exc)}
            if explain_text is None and hasattr(strategy, "explain_sql"):
                explain_stmt = strategy.explain_sql(top_k)
                if explain_stmt:
                    try:
                        explain_text = bridge.run_sql(explain_stmt).strip()
                    except ServiceUnavailable as exc:
                        explain_text = "EXPLAIN 失败: %s" % exc
        results.append(row)
        from exo_common import append_jsonl
        append_jsonl(results_path, row)
        emit("%2d/%d %s %s：%s（hit=%d，%.0f ms）" % (
            position, len(questions), question.get("id"), outcome.get("status"),
            question.get("category"), row["hitCount"], row["retrievalLatencyMs"]))

    summary = build_summary(questions, results, k_values)
    summary["counts"] = dataset_stats(questions)
    summary["variant"] = variant_name
    summary["strategy"] = strategy.describe()
    summary["index"] = {
        "rowCount": index_row_count,
        "buildStats": index_stats,
        "planDiagnosticMismatch": diagnostic_mismatch,
    }
    summary["dataset"] = {
        "path": relpath(dataset_path),
        "sha256": sha256_file(dataset_path),
        "testSplitSha256": test_split_fingerprint(questions),
    }
    failures_path = os.path.join(out_dir, "failures.jsonl")
    failure_rows = []
    for entry in summary["failures"]["serviceFailures"]:
        failure_rows.append({"type": "service-failure", "id": entry.get("id"),
                             "errorCode": entry.get("errorCode"), "message": entry.get("message")})
    for entry in summary["failures"]["answerableMisses"]:
        failure_rows.append({"type": "answerable-miss", "id": entry.get("id"),
                             "category": entry.get("category"), "split": entry.get("split"),
                             "question": entry.get("question"), "goldUnits": entry.get("goldUnits"),
                             "top1": entry.get("top1")})
    for entry in summary["failures"]["unanswerableWithHits"]:
        failure_rows.append({"type": "unanswerable-returned-hits", "id": entry.get("id"),
                             "hitCount": entry.get("hitCount")})
    write_jsonl(failures_path, failure_rows)
    write_json(os.path.join(out_dir, "summary.json"), round_floats(summary, 6))

    requests_path = os.path.join(out_dir, "requests.jsonl")
    write_jsonl(requests_path, list(embedder.records))
    sql_dir = ensure_dir(os.path.join(out_dir, "sql"))
    if production_sql:
        with open(os.path.join(sql_dir, "production_query.sql"), "w", encoding="utf-8", newline="\n") as handle:
            handle.write(production_sql + "\n")
    if diagnostic_sql:
        with open(os.path.join(sql_dir, "plan_diagnostic.sql"), "w", encoding="utf-8", newline="\n") as handle:
            handle.write(diagnostic_sql + "\n")
    if explain_text:
        with open(os.path.join(sql_dir, "explain.txt"), "w", encoding="utf-8", newline="\n") as handle:
            handle.write(explain_text + "\n")
    write_json(os.path.join(out_dir, "sections.json"), section_table(snapshot))

    database = bridge.describe()
    db_version = None
    pgvector_version = None
    try:
        db_version = bridge.scalar("SELECT version();")
        pgvector_version = bridge.scalar("SELECT extversion FROM pg_extension WHERE extname = 'vector';")
    except (ServiceUnavailable, EvalError) as exc:
        db_version = "查询失败: %s" % exc
    manifest = {
        "schemaVersion": 1,
        "runId": "%s-%s-%s" % (variant_name, snapshot.version, time.strftime("%Y%m%dT%H%M%SZ", time.gmtime(started))),
        "startedAt": started_iso,
        "finishedAt": utc_now_iso(),
        "wallClockSeconds": round(time.time() - started, 3),
        "code": git_info(),
        "corpusVersion": snapshot.version,
        "corpusManifestPath": relpath(manifest_path),
        "corpusManifestSha256": snapshot.manifest_sha256,
        "corpusDocumentSha256": snapshot.document_sha256(),
        "sectionCount": snapshot.section_count(),
        "datasetPath": relpath(dataset_path),
        "datasetSha256": sha256_file(dataset_path),
        "datasetTestSplitSha256": test_split_fingerprint(questions),
        "questionCount": len(questions),
        "variant": variant_name,
        "strategy": strategy.describe(),
        "index": {
            "indexVersion": getattr(strategy, "index_version", variant_name),
            "indexRevision": getattr(strategy, "index_revision", None),
            "kbId": getattr(strategy, "kb_id", None),
            "rowCount": index_row_count,
            "buildStats": index_stats,
        },
        "embedding": embedder.describe(),
        "embeddingApiKeyEnv": key_env,
        "embeddingApiKeyPresent": bool(os.environ.get(key_env)) if key_env != "injected" else True,
        "database": dict(database, postgresVersion=db_version, pgvectorVersion=pgvector_version),
        "execution": {
            "concurrency": int(run_cfg.get("concurrency") or 1),
            "warmup": warmup,
            "kValues": list(k_values),
            "topK": top_k,
            "planDiagnostic": bool(plan_diagnostic),
            "cache": "无缓存：每题单独调用 Embedding 与一次向量查询",
            "rateLimit": "未启用限流；顺序执行",
        },
        "environment": environment_info(),
        "outputs": {
            "results": relpath(results_path),
            "summary": relpath(os.path.join(out_dir, "summary.json")),
            "failures": relpath(failures_path),
            "requests": relpath(requests_path),
            "sql": relpath(sql_dir),
        },
    }
    write_json(os.path.join(out_dir, "manifest.json"), round_floats(manifest, 6))
    with open(os.path.join(out_dir, "run.log"), "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(log_lines) + "\n")
    return {"manifest": manifest, "summary": summary, "results": results, "log": log_lines}


def main(argv=None):
    args = parse_args(argv)
    try:
        config = load_config(args.config)
    except EvalError as exc:
        sys.stderr.write(str(exc) + "\n")
        return 3
    manifest_path, dataset_path = resolve_paths(config, args)
    try:
        snapshot = CorpusSnapshot(manifest_path)
        questions = load_questions(dataset_path)
    except EvalError as exc:
        sys.stderr.write(str(exc) + "\n")
        return 2
    freeze = None
    if args.freeze and os.path.isfile(args.freeze):
        freeze = load_json(args.freeze)
    problems = validate_questions(questions, snapshot, dataset_path=os.path.abspath(dataset_path), freeze=freeze)
    if problems:
        sys.stderr.write("题集校验失败，拒绝运行:\n- " + "\n- ".join(problems) + "\n")
        return 2
    try:
        output = run_evaluation(
            config, dataset_path, args.variant, args.out, manifest_path,
            build_index=args.build_index, limit=args.limit,
            snapshot=snapshot, questions=questions,
            plan_diagnostic=not args.no_plan_diagnostic, log=lambda line: sys.stdout.write(line + "\n"),
        )
    except EvalError as exc:
        sys.stderr.write(str(exc) + "\n")
        return 3
    summary = output["summary"]
    counts = summary["counts"]
    sys.stdout.write("\n题数 %d（有答案 %d / 资料不足 %d），variant=%s\n" % (
        counts["total"], counts["answerable"], counts["unanswerable"], args.variant))
    for key in sorted(summary["metricsAll"].keys()):
        bucket = summary["metricsAll"][key]
        sys.stdout.write("%s: Hit=%.3f Recall=%.3f MRR=%.3f（n=%d，gold=%d）\n" % (
            key, bucket["hitAt"] or 0.0, bucket["recallAt"] or 0.0, bucket["mrrAt"] or 0.0,
            bucket["questions"], bucket["goldUnits"]))
    no_answer = summary["noAnswer"]
    sys.stdout.write("无答案题 %d：检索拒绝 %d（%s），服务故障 %d\n" % (
        no_answer["total"], no_answer["retrievalRejected"],
        "%.3f" % no_answer["retrievalRejectionRate"] if no_answer["retrievalRejectionRate"] is not None else "n/a",
        no_answer["serviceFailures"]))
    latency = summary["latency"]["retrieval"]
    sys.stdout.write("检索延迟: n=%d p50=%.0fms p95=%.0fms max=%.0fms\n" % (
        latency["n"], latency["p50"] or 0.0, latency["p95"] or 0.0, latency["max"] or 0.0))
    sys.stdout.write("结果目录: %s\n" % relpath(args.out))
    if args.json:
        print_json(summary)
    unavailable = summary["failures"]["serviceFailures"]
    if len(unavailable) == len(questions):
        sys.stderr.write("全部题目均为服务故障：本次未取得真实检索结果\n")
        return 4
    if unavailable:
        sys.stdout.write("服务故障题目 %d 条，详见 failures.jsonl\n" % len(unavailable))
    return 0


if __name__ == "__main__":
    sys.exit(main())