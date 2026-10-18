# -*- coding: utf-8 -*-
"""`diagnose_boundaries.py` 的离线回归：窗口计算与四类归因。

这个脚本只用于解释命中差距（非冻结口径），但它的结论会写进交付记录，所以窗口边界、
"boundary-only" 与 "no-hit-in-doc" 的判定必须有可重复的夹具，避免把标题/空行算错。
"""

import io
import json
import os

from diagnose_boundaries import classify, effective_window, facts_in, range_covers

DOC_KEY = "alpha"
UNITS = [
    {"docKey": DOC_KEY, "sourceHash": "a" * 64, "startLine": 1, "endLine": 5,
     "role": "required", "facts": ["Alpha 使用 Java 21"]},
    {"docKey": DOC_KEY, "sourceHash": "a" * 64, "startLine": 9, "endLine": 9,
     "role": "required", "facts": ["最多返回 3 个片段"]},
]


class FakeSnapshot(object):
    def __init__(self, lines):
        self._lines = lines

    def lines(self, doc_key):
        assert doc_key == DOC_KEY
        return self._lines


def snapshot():
    return FakeSnapshot([
        "# Alpha 文档",        # 1 标题
        "",                     # 2 空行
        "Alpha 使用 Java 21",  # 3 正文
        "",                     # 4 空行
        "向量维度是 1024 维",   # 5 正文
        "",                     # 6
        "## 检索参数",          # 7 标题
        "",                     # 8
        "最多返回 3 个片段",     # 9 正文
    ])


def hit(start, end, payload):
    return {
        "rank": 1,
        "docKey": DOC_KEY,
        "sourceHash": "a" * 64,
        "startLine": start,
        "endLine": end,
        "payload": payload,
    }


def write_results(tmpdir, hits, question_id="fx-001"):
    path = os.path.join(str(tmpdir), "results.jsonl")
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps({"id": question_id, "hits": hits}, ensure_ascii=False) + "\n")
    return str(tmpdir)


def test_effective_window_skips_headings_and_blank_lines():
    assert effective_window(snapshot(), UNITS[0]) == (3, 5)


def test_effective_window_is_none_when_only_headings():
    assert effective_window(snapshot(), {"docKey": DOC_KEY, "startLine": 7, "endLine": 8}) == (None, None)


def test_range_covers_requires_full_containment_and_same_document():
    unit = UNITS[0]
    assert range_covers(hit(1, 5, ""), 1, 5, unit) is True
    assert range_covers(hit(3, 5, ""), 1, 5, unit) is False
    assert range_covers(hit(1, 5, ""), 1, 5, dict(unit, docKey="beta")) is False


def test_facts_in_normalises_whitespace():
    assert facts_in(hit(3, 5, "Alpha   使用 Java 21"), UNITS[0]) is True
    assert facts_in(hit(3, 5, "Alpha 使用 Java 17"), UNITS[0]) is False


def test_boundary_only_is_counted_when_body_window_is_covered(tmpdir):
    # 片段只覆盖正文窗口 3..5（gold 是 1..5，含标题与空行）
    run_dir = write_results(tmpdir, [hit(3, 5, "Alpha 使用 Java 21 向量维度是 1024 维")])
    report = classify(run_dir, [{"id": "fx-001", "answerable": True, "relevantEvidence": UNITS}], snapshot())
    assert report["buckets"]["covered"] == 0
    assert report["buckets"]["boundary-only"] == 1
    assert report["frozenHitAt"] == 0.0
    assert report["bodyWindowHitAt"] == 1.0
    assert report["examples"][0]["kind"] == "boundary-only"


def test_no_hit_in_doc_is_counted_when_topk_has_no_chunk_from_the_document(tmpdir):
    foreign = dict(hit(3, 5, "别的文档"), docKey="beta")
    run_dir = write_results(tmpdir, [foreign])
    report = classify(run_dir, [{"id": "fx-001", "answerable": True, "relevantEvidence": UNITS}], snapshot())
    assert report["buckets"]["no-hit-in-doc"] == 2
    assert report["bodyWindowHitAt"] == 0.0


def test_full_containment_counts_as_covered(tmpdir):
    run_dir = write_results(tmpdir, [
        hit(1, 5, "Alpha 使用 Java 21 向量维度是 1024 维"),
        hit(9, 9, "最多返回 3 个片段"),
    ])
    report = classify(run_dir, [{"id": "fx-001", "answerable": True, "relevantEvidence": UNITS}], snapshot())
    assert report["buckets"]["covered"] == 2
    assert report["frozenHitAt"] == 1.0
