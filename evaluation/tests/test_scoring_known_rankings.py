# -*- coding: utf-8 -*-
"""命中判据与指标口径的已知排名夹具（纯离线，不访问模型与数据库）。

覆盖：空结果、多证据（部分/全部覆盖）、重复片段、超时与故障、无答案拒绝率、
延迟分位数，以及 build_summary 的类别细分不再被误过滤的回归。
"""

import pytest

from exo_common import latency_stats, percentile
from exo_scoring import (
    STATUS_NO_MATCH,
    STATUS_OK,
    STATUS_UNAVAILABLE,
    aggregate,
    build_summary,
    failure_summary,
    hit_covers_unit,
    no_answer_stats,
    score_question,
    tie_report,
)

HASH_A = "hash-docA"
HASH_B = "hash-docB"
HASH_C = "hash-docC"


def unit(doc_key, start, end, facts, role="required"):
    return {
        "docKey": doc_key,
        "sourceHash": "hash-" + doc_key,
        "startLine": start,
        "endLine": end,
        "role": role,
        "facts": list(facts),
    }


def hit(rank, doc_key, start, end, payload, distance=0.5, source_hash=None):
    return {
        "rank": rank,
        "chunkId": "chunk-%d" % rank,
        "docKey": doc_key,
        "sourceHash": "hash-" + doc_key if source_hash is None else source_hash,
        "startLine": start,
        "endLine": end,
        "distance": distance,
        "payload": payload,
        "payloadSha256": "x",
    }


def question(qid, units, answerable=True, category="body-fact", split="dev"):
    return {
        "id": qid,
        "split": split,
        "groupId": "g-" + qid,
        "category": category,
        "question": "夹具问题",
        "answerable": answerable,
        "corpusVersion": "fixture",
        "relevantEvidence": units,
        "expectedFacts": ["夹具事实"],
    }


def result(status, hits):
    return {"status": status, "hits": hits, "errorCode": None, "message": None}


MULTI = question("q-multi", [
    unit("docA", 10, 20, ["事实 A"]),
    unit("docB", 5, 8, ["事实 B"]),
])
SINGLE = question("q-single", [unit("docC", 1, 3, ["事实 C"])])


# ---------------------------------------------------------------------------
# 命中判据
# ---------------------------------------------------------------------------

def test_hit_requires_full_span_containment():
    evidence = unit("docA", 10, 20, ["事实 A"])
    assert hit_covers_unit(hit(1, "docA", 10, 20, "正文含事实 A"), evidence) is True
    assert hit_covers_unit(hit(1, "docA", 5, 25, "正文含事实 A"), evidence) is True
    # 只与部分行相交不算命中
    assert hit_covers_unit(hit(1, "docA", 10, 15, "正文含事实 A"), evidence) is False
    assert hit_covers_unit(hit(1, "docA", 15, 30, "正文含事实 A"), evidence) is False


def test_hit_requires_payload_to_contain_all_facts():
    evidence = unit("docA", 10, 20, ["事实 A", "事实 B"])
    assert hit_covers_unit(hit(1, "docA", 10, 20, "事实 A 与事实 B 都在"), evidence) is True
    assert hit_covers_unit(hit(1, "docA", 10, 20, "只有事实 A"), evidence) is False


def test_hit_requires_matching_document_and_hash():
    evidence = unit("docA", 10, 20, ["事实 A"])
    assert hit_covers_unit(hit(1, "docB", 10, 20, "事实 A"), evidence) is False
    assert hit_covers_unit(hit(1, "docA", 10, 20, "事实 A", source_hash="other"), evidence) is False
    # 任一侧缺少 hash 时不比对（只能按行范围与事实核验）
    assert hit_covers_unit(hit(1, "docA", 10, 20, "事实 A", source_hash=""), evidence) is True


def test_hit_without_position_or_payload_is_not_verifiable():
    evidence = unit("docA", 10, 20, ["事实 A"])
    missing_lines = hit(1, "docA", 10, 20, "事实 A")
    missing_lines["startLine"] = None
    assert hit_covers_unit(missing_lines, evidence) is False
    missing_payload = hit(1, "docA", 10, 20, None)
    assert hit_covers_unit(missing_payload, evidence) is False


# ---------------------------------------------------------------------------
# 空结果 / 多证据 / 重复片段
# ---------------------------------------------------------------------------

def test_empty_results_score_zero():
    empty = result(STATUS_NO_MATCH, [])
    detail = score_question(MULTI, empty, 3)
    assert detail["hit"] is False
    assert detail["recall"] == 0.0
    assert detail["reciprocalRank"] == 0.0
    assert detail["goldUnitsCovered"] == 0
    metrics = aggregate([MULTI], {"q-multi": empty}, [1, 3])
    assert metrics["k=1"]["hitAt"] == 0.0
    assert metrics["k=1"]["recallAt"] == 0.0
    assert metrics["k=1"]["mrrAt"] == 0.0
    assert metrics["k=3"]["goldUnits"] == 2
    assert metrics["k=3"]["goldUnitsCovered"] == 0


def test_multi_evidence_partial_and_full_coverage():
    partial = result(STATUS_OK, [hit(1, "docA", 10, 20, "正文含事实 A")])
    detail = score_question(MULTI, partial, 3)
    assert detail["hit"] is True
    assert detail["recall"] == 0.5
    assert detail["reciprocalRank"] == 1.0
    assert detail["crossDocumentCoverage"] == 0.5
    assert detail["allRequiredDocumentsCovered"] is False

    full = result(STATUS_OK, [
        hit(1, "docA", 10, 20, "正文含事实 A"),
        hit(2, "docB", 5, 8, "正文含事实 B", distance=0.7),
    ])
    detail_full = score_question(MULTI, full, 3)
    assert detail_full["recall"] == 1.0
    assert detail_full["crossDocumentCoverage"] == 1.0
    assert detail_full["allRequiredDocumentsCovered"] is True

    metrics = aggregate([MULTI], {"q-multi": full}, [1, 2])
    assert metrics["k=1"]["recallAt"] == 0.5
    assert metrics["k=1"]["microRecall"] == 0.5
    assert metrics["k=2"]["recallAt"] == 1.0
    assert metrics["k=2"]["microRecall"] == 1.0
    assert metrics["k=2"]["allRequiredDocumentsCoveredRateAt"] == 1.0
    assert metrics["k=2"]["crossDocumentQuestions"] == 1


def test_only_second_document_covered_gives_half_mrr():
    only_second = result(STATUS_OK, [hit(2, "docB", 5, 8, "正文含事实 B")])
    detail = score_question(MULTI, only_second, 3)
    assert detail["hit"] is True
    assert detail["recall"] == 0.5
    assert detail["reciprocalRank"] == 0.5
    # k=1 时该命中被截断，指标归零
    assert score_question(MULTI, only_second, 1)["hit"] is False


def test_duplicate_fragments_are_counted_once():
    duplicated = result(STATUS_OK, [
        hit(1, "docC", 1, 3, "正文含事实 C", distance=0.5),
        hit(2, "docC", 1, 3, "正文含事实 C", distance=0.5),
    ])
    detail = score_question(SINGLE, duplicated, 3)
    assert detail["goldUnitsCovered"] == 1
    assert detail["recall"] == 1.0
    assert detail["reciprocalRank"] == 1.0
    metrics = aggregate([SINGLE], {"q-single": duplicated}, [1, 3])
    assert metrics["k=3"]["goldUnitsCovered"] == 1
    assert metrics["k=3"]["microRecall"] == 1.0
    assert tie_report([{"id": "q-single", "hits": duplicated["hits"]}])["questionsWithIdenticalDistances"] == 1

    # 重复片段只在第 2 名出现：覆盖率满分，但 MRR 只取第一个正确排名
    second_only = result(STATUS_OK, [
        hit(1, "docA", 1, 3, "无关正文", distance=0.2),
        hit(2, "docC", 1, 3, "正文含事实 C", distance=0.5),
    ])
    detail_second = score_question(SINGLE, second_only, 3)
    assert detail_second["recall"] == 1.0
    assert detail_second["reciprocalRank"] == 0.5


def test_distractor_units_are_tracked_separately():
    with_distractor = question("q-distractor", [
        unit("docA", 10, 20, ["事实 A"]),
        unit("docB", 5, 8, ["错误事实"], role="distractor"),
    ])
    got_distractor = result(STATUS_OK, [hit(1, "docB", 5, 8, "错误事实在这里", distance=0.1)])
    detail = score_question(with_distractor, got_distractor, 3)
    assert detail["hit"] is False
    assert detail["distractorUnits"] == 1
    assert detail["distractorUnitsRetrieved"] == 1
    metrics = aggregate([with_distractor], {"q-distractor": got_distractor}, [3])
    assert metrics["k=3"]["distractorTopkRate"] == 1.0


# ---------------------------------------------------------------------------
# 超时与故障
# ---------------------------------------------------------------------------

def test_service_failure_is_not_a_rejection():
    failed = {"status": STATUS_UNAVAILABLE, "errorCode": "EMBEDDING_UNAVAILABLE",
              "message": "连接超时", "hits": []}
    answerable = question("q-answerable", [unit("docA", 10, 20, ["事实 A"])])
    unanswerable = question("q-unanswerable", [], answerable=False, category="insufficient-info")
    questions = [answerable, unanswerable]
    results = {"q-answerable": failed, "q-unanswerable": failed}

    detail = score_question(answerable, failed, 3)
    assert detail["hit"] is False
    assert detail["recall"] == 0.0
    assert detail["reciprocalRank"] == 0.0
    metrics = aggregate(questions, results, [3])
    assert metrics["k=3"]["questions"] == 1
    assert metrics["k=3"]["hitAt"] == 0.0

    stats = no_answer_stats(questions, results)
    assert stats["total"] == 1
    assert stats["serviceFailures"] == 1
    assert stats["retrievalRejected"] == 0
    assert stats["retrievalRejectionRate"] == 0.0
    assert stats["retrievalRejectionRateExcludingFailures"] is None

    failures = failure_summary(questions, results)
    assert [row["id"] for row in failures["serviceFailures"]] == ["q-answerable", "q-unanswerable"]
    assert failures["answerableMisses"] == []


def test_no_answer_buckets_and_rejection_rate():
    questions = [
        question("q-na1", [], answerable=False, category="insufficient-info"),
        question("q-na2", [], answerable=False, category="insufficient-info"),
        question("q-na3", [], answerable=False, category="insufficient-info"),
        question("q-na4", [], answerable=False, category="insufficient-info"),
    ]
    results = {
        "q-na1": result(STATUS_NO_MATCH, []),
        "q-na2": result(STATUS_OK, [hit(1, "docA", 1, 3, "无关正文")]),
        "q-na3": result(STATUS_OK, []),
        "q-na4": {"status": STATUS_UNAVAILABLE, "hits": [], "errorCode": "DB_UNAVAILABLE", "message": "超时"},
    }
    stats = no_answer_stats(questions, results)
    assert stats["total"] == 4
    assert stats["retrievalRejected"] == 1
    assert stats["retrievalRejectionRate"] == pytest.approx(0.25)
    assert stats["retrievalRejectionRateExcludingFailures"] == pytest.approx(1.0 / 3.0)
    assert stats["serviceFailures"] == 1
    assert stats["okButEmpty"] == 1
    assert stats["okWithHits"] == 1
    assert stats["answerLevelRejection"] is None

    failures = failure_summary(questions, results)
    assert [row["id"] for row in failures["unanswerableWithHits"]] == ["q-na2"]


# ---------------------------------------------------------------------------
# 延迟与汇总
# ---------------------------------------------------------------------------

def test_latency_percentiles_on_known_values():
    values = [10.0, 20.0, 30.0, 40.0]
    assert percentile(values, 0.5) == pytest.approx(25.0)
    assert percentile(values, 0.95) == pytest.approx(38.5)
    assert percentile([], 0.5) is None
    assert percentile([7.0], 0.95) == 7.0
    stats = latency_stats(values)
    assert stats["n"] == 4
    assert stats["min"] == 10.0
    assert stats["max"] == 40.0
    assert stats["mean"] == pytest.approx(25.0)


def test_build_summary_reports_category_breakdown_and_latency():
    first = question("q-cat-1", [unit("docA", 10, 20, ["事实 A"])], category="title-fact")
    second = question("q-cat-2", [unit("docB", 5, 8, ["事实 B"])], category="paraphrase-zh")
    rows = [
        {"id": "q-cat-1", "status": STATUS_OK, "hits": [hit(1, "docA", 10, 20, "正文含事实 A")],
         "retrievalLatencyMs": 100.0, "embeddingLatencyMs": 60.0},
        {"id": "q-cat-2", "status": STATUS_NO_MATCH, "hits": [],
         "retrievalLatencyMs": 300.0, "embeddingLatencyMs": 80.0},
    ]
    summary = build_summary([first, second], rows, [1, 3])
    assert summary["questionCount"] == 2
    assert summary["answerableQuestions"] == 2
    # 回归：byCategory 曾经被误当成 split 过滤掉，导致整段为空
    assert summary["byCategory"]["title-fact"]["questions"] == 1
    assert summary["byCategory"]["title-fact"]["hitAt"] == 1.0
    assert summary["byCategory"]["paraphrase-zh"]["hitAt"] == 0.0
    assert summary["metricsAll"]["k=1"]["mrrAt"] == pytest.approx(0.5)
    assert summary["latency"]["retrieval"]["n"] == 2
    assert summary["latency"]["retrieval"]["p50"] == pytest.approx(200.0)
    assert summary["failures"]["answerableMisses"][0]["id"] == "q-cat-2"
