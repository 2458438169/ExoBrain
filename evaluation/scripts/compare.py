"""同条件对比两次真实运行的结果目录。

用法：
  python evaluation/scripts/compare.py --baseline <旧结果目录> --candidate <新结果目录> [--out <文件前缀>]

要求两次运行使用同一语料、同一题集、同一模型版本、同一 k 与同一环境；不一致时打印警告并在报告中标记"不可比"。
退出码：0 正常；4 结果目录缺少 manifest.json / summary.json。
"""

import argparse
import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from exo_common import load_json, relpath, round_floats, write_json

METRIC_FIELDS = (
    ("hitAt", "Hit@%d"),
    ("recallAt", "Recall@%d"),
    ("mrrAt", "MRR@%d"),
    ("crossDocumentCoverageAt", "跨文档覆盖率@%d"),
    ("microRecall", "微平均 Recall@%d"),
)


def parse_args(argv):
    parser = argparse.ArgumentParser(description="对比两次 ExoBrain 检索评测运行")
    parser.add_argument("--baseline", required=True, help="基线结果目录（例如 legacy-title-v1）")
    parser.add_argument("--candidate", required=True, help="候选结果目录")
    parser.add_argument("--out", default=None, help="报告前缀（默认写到候选目录）")
    parser.add_argument("--k", type=int, default=3, help="报告使用的 k（默认 3）")
    return parser.parse_args(argv)


def load_run(directory):
    manifest_path = os.path.join(directory, "manifest.json")
    summary_path = os.path.join(directory, "summary.json")
    if not os.path.isfile(manifest_path) or not os.path.isfile(summary_path):
        raise IOError("结果目录缺少 manifest.json 或 summary.json: %s" % directory)
    run = {
        "dir": directory,
        "manifest": load_json(manifest_path),
        "summary": load_json(summary_path),
        "resultsPath": os.path.join(directory, "results.jsonl"),
    }
    return run


def comparability(baseline, candidate):
    checks = []
    pairs = (
        ("datasetSha256", "题集 SHA-256"),
        ("corpusVersion", "语料版本"),
        ("questionCount", "题数"),
    )
    for key, label in pairs:
        left = baseline["manifest"].get(key)
        right = candidate["manifest"].get(key)
        checks.append({"field": key, "label": label, "baseline": left, "candidate": right, "same": left == right})
    left_model = (baseline["manifest"].get("embedding") or {}).get("model")
    right_model = (candidate["manifest"].get("embedding") or {}).get("model")
    checks.append({"field": "embedding.model", "label": "Embedding 模型", "baseline": left_model,
                   "candidate": right_model, "same": left_model == right_model})
    left_k = (baseline["manifest"].get("execution") or {}).get("kValues")
    right_k = (candidate["manifest"].get("execution") or {}).get("kValues")
    checks.append({"field": "execution.kValues", "label": "k 取值", "baseline": left_k,
                   "candidate": right_k, "same": left_k == right_k})
    left_topk = (baseline["manifest"].get("execution") or {}).get("topK")
    right_topk = (candidate["manifest"].get("execution") or {}).get("topK")
    checks.append({"field": "execution.topK", "label": "topK", "baseline": left_topk,
                   "candidate": right_topk, "same": left_topk == right_topk})
    return checks


def metric_pairs(baseline_summary, candidate_summary, k):
    key = "k=%d" % k
    rows = []
    for scope_label, scope_key in (("全部", "metricsAll"), ("开发集", "metricsDev"), ("保留测试集", "metricsTest")):
        left = (baseline_summary.get(scope_key) or {}).get(key) or {}
        right = (candidate_summary.get(scope_key) or {}).get(key) or {}
        for field, template in METRIC_FIELDS:
            left_value = left.get(field)
            right_value = right.get(field)
            delta = None
            if isinstance(left_value, (int, float)) and isinstance(right_value, (int, float)):
                delta = right_value - left_value
            rows.append({
                "scope": scope_label,
                "metric": template % k,
                "baseline": left_value,
                "candidate": right_value,
                "delta": delta,
                "baselineQuestions": left.get("questions"),
                "candidateQuestions": right.get("questions"),
            })
    for scope_label, scope_key in (("全部", "noAnswer"), ("保留测试集", "noAnswerTest")):
        left = baseline_summary.get(scope_key) or {}
        right = candidate_summary.get(scope_key) or {}
        for field, label in (("retrievalRejectionRate", "无答案检索拒绝率"),
                             ("serviceFailures", "服务故障题数"),
                             ("total", "无答案题数")):
            left_value = left.get(field)
            right_value = right.get(field)
            delta = None
            if isinstance(left_value, (int, float)) and isinstance(right_value, (int, float)):
                delta = right_value - left_value
            rows.append({"scope": scope_label, "metric": label, "baseline": left_value,
                         "candidate": right_value, "delta": delta})
    for scope_label, scope_key in (("全部", "latency"),):
        left = ((baseline_summary.get(scope_key) or {}).get("retrieval") or {})
        right = ((candidate_summary.get(scope_key) or {}).get("retrieval") or {})
        for field, label in (("p50", "检索延迟 p50 (ms)"), ("p95", "检索延迟 p95 (ms)"), ("n", "延迟样本量")):
            left_value = left.get(field)
            right_value = right.get(field)
            delta = None
            if isinstance(left_value, (int, float)) and isinstance(right_value, (int, float)):
                delta = right_value - left_value
            rows.append({"scope": scope_label, "metric": label, "baseline": left_value,
                         "candidate": right_value, "delta": delta})
    return rows


def format_value(value):
    if value is None:
        return "n/a"
    if isinstance(value, float):
        return "%.3f" % value
    return str(value)


def format_delta(value):
    if value is None:
        return "-"
    if isinstance(value, float):
        return "%+.3f" % value
    return "%+d" % value


def per_question_differences(baseline_dir, candidate_dir, limit=12):
    baseline_results = os.path.join(baseline_dir, "results.jsonl")
    candidate_results = os.path.join(candidate_dir, "results.jsonl")
    if not os.path.isfile(baseline_results) or not os.path.isfile(candidate_results):
        return []
    left = {}
    right = {}
    for path, target in ((baseline_results, left), (candidate_results, right)):
        with io.open(path, "r", encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                import json

                row = json.loads(line)
                target[row.get("id")] = row
    differences = []
    for question_id in sorted(set(left.keys()) | set(right.keys())):
        left_row = left.get(question_id) or {}
        right_row = right.get(question_id) or {}
        if left_row.get("status") != right_row.get("status"):
            differences.append({
                "id": question_id,
                "kind": "status",
                "baseline": left_row.get("status"),
                "candidate": right_row.get("status"),
            })
            continue
        left_top = (left_row.get("hits") or [{}])[0].get("docKey") if left_row.get("hits") else None
        right_top = (right_row.get("hits") or [{}])[0].get("docKey") if right_row.get("hits") else None
        if left_top != right_top:
            differences.append({
                "id": question_id,
                "kind": "top1-document",
                "baseline": left_top,
                "candidate": right_top,
            })
    return differences[:limit]


def build_report(baseline, candidate, k):
    checks = comparability(baseline, candidate)
    rows = metric_pairs(baseline["summary"], candidate["summary"], k)
    return {
        "schemaVersion": 1,
        "k": k,
        "baseline": {
            "dir": baseline["dir"],
            "variant": baseline["manifest"].get("variant"),
            "runId": baseline["manifest"].get("runId"),
            "indexRevision": (baseline["manifest"].get("index") or {}).get("indexRevision"),
            "serviceFailures": baseline["summary"].get("noAnswer", {}).get("serviceFailures"),
        },
        "candidate": {
            "dir": candidate["dir"],
            "variant": candidate["manifest"].get("variant"),
            "runId": candidate["manifest"].get("runId"),
            "indexRevision": (candidate["manifest"].get("index") or {}).get("indexRevision"),
            "serviceFailures": candidate["summary"].get("noAnswer", {}).get("serviceFailures"),
        },
        "comparability": checks,
        "comparable": all(check["same"] for check in checks),
        "metrics": rows,
        "perQuestionDifferences": per_question_differences(baseline["dir"], candidate["dir"]),
        "notes": [
            "两组结果必须来自同一语料、题集、模型版本与 k；任一项不同则不能声称单变量提升。",
            "指标差异不等于回答质量差异：回答事实支持度需要单独的人工 rubric 复核。",
        ],
    }


def render_markdown(report):
    lines = []
    lines.append("# 检索评测对比：%s → %s" % (report["baseline"]["variant"], report["candidate"]["variant"]))
    lines.append("")
    lines.append("- 基线: %s（%s）" % (report["baseline"]["dir"], report["baseline"]["runId"]))
    lines.append("- 候选: %s（%s）" % (report["candidate"]["dir"], report["candidate"]["runId"]))
    lines.append("- k = %d；可比性: %s" % (report["k"], "一致" if report["comparable"] else "存在差异，结果不可直接比较"))
    lines.append("")
    lines.append("| 范围 | 指标 | 基线 | 候选 | 差值 |")
    lines.append("| --- | --- | --- | --- | --- |")
    for row in report["metrics"]:
        lines.append("| %s | %s | %s | %s | %s |" % (
            row["scope"], row["metric"], format_value(row["baseline"]),
            format_value(row["candidate"]), format_delta(row["delta"])))
    lines.append("")
    lines.append("## 可比性检查")
    lines.append("")
    for check in report["comparability"]:
        lines.append("- %s %s: 基线=%s 候选=%s" % (
            "一致" if check["same"] else "不一致", check["label"],
            format_value(check["baseline"]), format_value(check["candidate"])))
    differences = report["perQuestionDifferences"]
    lines.append("")
    lines.append("## 逐题差异（前 %d 条，按题目 ID 排序）" % len(differences))
    lines.append("")
    if not differences:
        lines.append("无状态或 top1 文档变化。")
    else:
        for entry in differences:
            lines.append("- %s %s: %s → %s" % (
                entry["id"], entry["kind"], format_value(entry["baseline"]), format_value(entry["candidate"])))
    lines.append("")
    lines.append("## 说明")
    lines.append("")
    for note in report["notes"]:
        lines.append("- %s" % note)
    lines.append("")
    return "\n".join(lines)


def main(argv=None):
    args = parse_args(argv)
    try:
        baseline = load_run(args.baseline)
        candidate = load_run(args.candidate)
    except IOError as exc:
        sys.stderr.write(str(exc) + "\n")
        return 4
    report = build_report(baseline, candidate, args.k)
    prefix = args.out or os.path.join(args.candidate, "comparison")
    write_json(prefix + ".json", round_floats(report, 6))
    with io.open(prefix + ".md", "w", encoding="utf-8", newline="\n") as handle:
        handle.write(render_markdown(report))
    sys.stdout.write(render_markdown(report))
    sys.stdout.write("\n报告已写入: %s.md / %s.json\n" % (relpath(prefix), relpath(prefix)))
    return 0


if __name__ == "__main__":
    sys.exit(main())