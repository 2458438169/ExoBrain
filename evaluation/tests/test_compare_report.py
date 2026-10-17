# -*- coding: utf-8 -*-
"""对比脚本的离线夹具：可比性检查、指标差值与逐题差异。"""

import os

from exo_common import write_json, write_jsonl

import compare  # noqa: E402


def write_run(root, variant, dataset_sha, metrics, top1_doc, extra_manifest=None):
    directory = os.path.join(root, variant)
    if not os.path.isdir(directory):
        os.makedirs(directory)
    manifest = {
        "schemaVersion": 1,
        "runId": variant + "-run",
        "variant": variant,
        "datasetSha256": dataset_sha,
        "corpusVersion": "v1-2026-10-14",
        "questionCount": 1,
        "embedding": {"model": "BAAI/bge-m3"},
        "execution": {"kValues": [1, 3], "topK": 3},
        "index": {"indexRevision": variant + "/rev"},
    }
    if extra_manifest:
        manifest.update(extra_manifest)
    summary = {
        "metricsAll": {"k=3": metrics},
        "metricsDev": {"k=3": dict(metrics, questions=0)},
        "metricsTest": {"k=3": metrics},
        "noAnswer": {"retrievalRejectionRate": 0.0, "serviceFailures": 0, "total": 1},
        "noAnswerTest": {"retrievalRejectionRate": 0.0, "serviceFailures": 0, "total": 1},
        "latency": {"retrieval": {"n": 1, "p50": 100.0, "p95": 100.0}},
    }
    write_json(os.path.join(directory, "manifest.json"), manifest)
    write_json(os.path.join(directory, "summary.json"), summary)
    write_jsonl(os.path.join(directory, "results.jsonl"), [{
        "id": "q001", "status": "OK",
        "hits": [{"docKey": top1_doc, "startLine": 1, "endLine": 3, "distance": 0.5}],
    }])
    return directory


METRICS = {
    "questions": 1, "hitAt": 0.5, "recallAt": 0.5, "mrrAt": 0.5,
    "crossDocumentCoverageAt": 0.5, "microRecall": 0.5,
    "goldUnits": 2, "goldUnitsCovered": 1,
}


def test_comparable_runs_report_deltas(tmpdir):
    root = str(tmpdir)
    baseline = write_run(root, "legacy-title-v1", "sha-a", METRICS, "docA")
    candidate = write_run(root, "body-v1", "sha-a",
                          dict(METRICS, hitAt=1.0, mrrAt=0.75), "docB")
    report = compare.build_report(compare.load_run(baseline), compare.load_run(candidate), 3)
    assert report["comparable"] is True
    hits = [row for row in report["metrics"] if row["metric"] == "Hit@3" and row["scope"] == "全部"]
    assert len(hits) == 1
    assert hits[0]["baseline"] == 0.5
    assert hits[0]["candidate"] == 1.0
    assert hits[0]["delta"] == 0.5
    kinds = sorted(set(entry["kind"] for entry in report["perQuestionDifferences"]))
    assert kinds == ["top1-document"]
    markdown = compare.render_markdown(report)
    assert "可比性: 一致" in markdown


def test_different_dataset_makes_runs_incomparable(tmpdir):
    root = str(tmpdir)
    baseline = write_run(root, "legacy-title-v1", "sha-a", METRICS, "docA")
    candidate = write_run(root, "body-v1", "sha-b", METRICS, "docA")
    report = compare.build_report(compare.load_run(baseline), compare.load_run(candidate), 3)
    assert report["comparable"] is False
    failed = [check["label"] for check in report["comparability"] if not check["same"]]
    assert failed == ["题集 SHA-256"]
    assert "存在差异，结果不可直接比较" in compare.render_markdown(report)


def test_missing_run_files_reports_error(tmpdir):
    code = compare.main(["--baseline", str(tmpdir), "--candidate", str(tmpdir)])
    assert code == 4
