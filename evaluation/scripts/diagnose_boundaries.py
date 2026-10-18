# -*- coding: utf-8 -*-
"""诊断工具（非冻结口径）：解释 body-v1 与 legacy-title-v1 的命中差距来自哪里。

背景：`evaluation/README.md` 第 3 节的命中判据要求**单个** hit 的行范围完整包含 gold 单元
`[startLine, endLine]`。该判据是按 legacy 的「章节整段入向量」写的，而任务 03 的正文切块
把章节标题行排除在 chunk 正文之外、并把标题放进元数据 `headingPath`，同时裁剪首尾空行。
于是任何以标题行开头的 gold 单元在 body-v1 下都无法被单个片段「完整包含」——这是判据与
切块边界之间的结构性冲突，不是检索质量差异。

本脚本把每个 required 单元分到四类之一，只用于解释，不改变、也不替代冻结指标：

- `covered`         ：冻结判据下已覆盖；
- `boundary-only`   ：存在片段覆盖「去掉首尾标题/空行后的有效正文窗口」，且该片段正文包含
                      全部事实字符串——只差标题/空行边界；
- `facts-missing`   ：行范围够，但事实不在该片段正文里；
- `partial-overlap` ：只有部分重叠；
- `no-hit-in-doc`   ：top-k 里根本没有该文档的片段（真实检索缺口）。

用法（仓库根目录执行）：
  python evaluation/scripts/diagnose_boundaries.py \
      --baseline evaluation/runs/legacy-title-v1 \
      --candidate evaluation/runs/body-v1 \
      --out evaluation/runs/body-v1/boundary-diagnostic.json
"""

import argparse
import io
import json
import os
import sys

SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
EVAL_DIR = os.path.dirname(SCRIPTS_DIR)
REPO_DIR = os.path.dirname(EVAL_DIR)
if SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, SCRIPTS_DIR)

from exo_common import norm_ws  # noqa: E402
from exo_corpus import CorpusSnapshot  # noqa: E402
from exo_scoring import units_of_role  # noqa: E402

DEFAULT_MANIFEST = os.path.join(EVAL_DIR, "corpus", "v1-2026-10-14", "manifest.json")
DEFAULT_DATASET = os.path.join(EVAL_DIR, "datasets", "questions-v1.jsonl")


def load_jsonl(path):
    with io.open(path, encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def load_results(run_dir):
    return {row["id"]: row for row in load_jsonl(os.path.join(run_dir, "results.jsonl"))}


def effective_window(snapshot, unit):
    """单元内第一行到最后一行的非空、非标题行；全为空/标题时返回 (None, None)。"""
    lines = snapshot.lines(unit["docKey"])
    start, end = None, None
    for line_no in range(unit["startLine"], unit["endLine"] + 1):
        stripped = lines[line_no - 1].strip()
        if not stripped or stripped.startswith("#"):
            continue
        if start is None:
            start = line_no
        end = line_no
    return start, end


def range_covers(hit, start, end, unit):
    if hit.get("docKey") != unit.get("docKey"):
        return False
    if hit.get("sourceHash") and unit.get("sourceHash") and hit["sourceHash"] != unit["sourceHash"]:
        return False
    if hit.get("startLine") is None or hit.get("endLine") is None:
        return False
    return hit["startLine"] <= start and hit["endLine"] >= end


def facts_in(hit, unit):
    text = norm_ws(hit.get("payload") or "")
    return all(norm_ws(fact) in text for fact in unit.get("facts") or [])


def classify(run_dir, questions, snapshot):
    results = load_results(run_dir)
    answerable = [q for q in questions if q.get("answerable")]
    buckets = {"covered": 0, "boundary-only": 0, "facts-missing": 0,
               "partial-overlap": 0, "no-hit-in-doc": 0}
    examples = []
    frozen_questions = 0
    window_questions = 0
    for question in answerable:
        units = units_of_role(question, "required")
        hits = (results.get(question["id"]) or {}).get("hits") or []
        frozen_ok = False
        window_ok = False
        for unit in units:
            if any(range_covers(h, unit["startLine"], unit["endLine"], unit) and facts_in(h, unit)
                   for h in hits):
                buckets["covered"] += 1
                frozen_ok = True
                window_ok = True
                continue
            start, end = effective_window(snapshot, unit)
            if start is not None:
                window_hits = [h for h in hits if range_covers(h, start, end, unit)]
                if window_hits and any(facts_in(h, unit) for h in window_hits):
                    buckets["boundary-only"] += 1
                    window_ok = True
                    if len(examples) < 8:
                        examples.append({
                            "id": question["id"], "kind": "boundary-only",
                            "docKey": unit["docKey"],
                            "unit": [unit["startLine"], unit["endLine"]],
                            "window": [start, end],
                            "hit": [window_hits[0].get("startLine"), window_hits[0].get("endLine")],
                        })
                    continue
                if window_hits:
                    buckets["facts-missing"] += 1
                    continue
            doc_hits = [h for h in hits if h.get("docKey") == unit.get("docKey")]
            if doc_hits:
                buckets["partial-overlap"] += 1
            else:
                buckets["no-hit-in-doc"] += 1
                if len(examples) < 12:
                    examples.append({
                        "id": question["id"], "kind": "no-hit-in-doc",
                        "docKey": unit["docKey"],
                        "unit": [unit["startLine"], unit["endLine"]],
                        "hits": [[h.get("docKey"), h.get("startLine"), h.get("endLine")] for h in hits],
                    })
        if frozen_ok:
            frozen_questions += 1
        if window_ok:
            window_questions += 1
    n = float(len(answerable)) or 1.0
    total = sum(buckets.values())
    return {
        "run": os.path.basename(os.path.normpath(run_dir)),
        "answerableQuestions": len(answerable),
        "requiredUnits": total,
        "buckets": buckets,
        "frozenHitAt": round(frozen_questions / n, 6),
        "bodyWindowHitAt": round(window_questions / n, 6),
        "unitsCoveredOnlyWithBodyWindow": total - buckets["covered"] - buckets["facts-missing"]
                                          - buckets["partial-overlap"] - buckets["no-hit-in-doc"],
        "examples": examples,
    }


def main():
    parser = argparse.ArgumentParser(description="解释 retrieval 命中的边界差异（只读，不改冻结指标）")
    parser.add_argument("--baseline", default=os.path.join(EVAL_DIR, "runs", "legacy-title-v1"))
    parser.add_argument("--candidate", default=os.path.join(EVAL_DIR, "runs", "body-v1"))
    parser.add_argument("--dataset", default=DEFAULT_DATASET)
    parser.add_argument("--corpus-manifest", default=DEFAULT_MANIFEST)
    parser.add_argument("--out", default=None, help="把 JSON 报告写到该路径")
    args = parser.parse_args()

    snapshot = CorpusSnapshot(args.corpus_manifest)
    questions = load_jsonl(args.dataset)
    report = {
        "schemaVersion": 1,
        "note": "非冻结口径；冻结指标见 summary.json 与 compare.py 的输出。",
        "runs": [classify(args.baseline, questions, snapshot),
                 classify(args.candidate, questions, snapshot)],
    }
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.out:
        target = os.path.abspath(args.out)
        parent = os.path.dirname(target)
        if parent and not os.path.isdir(parent):
            os.makedirs(parent)
        with io.open(target, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text + "\n")
    print(text)


if __name__ == "__main__":
    main()
