# -*- coding: utf-8 -*-
"""校验器的失败路径：重复 ID、失效行号、hash 漂移、缺少 gold、split 重叠等必须失败退出。

两种粒度：
- 单元级：直接调用 exo_dataset.validate_questions，断言具体问题描述；
- 命令级：调用 validate.main（与开发者实际执行的命令一致），断言退出码为 2。
"""

import os
import sys

import pytest

import validate  # noqa: E402  （由 conftest.py 把 evaluation/scripts 加入 sys.path）

from conftest import (  # noqa: E402
    ALPHA_RETRIEVAL_LINE,
    ALPHA_SECTIONS,
    ALPHA_VERSION_LINE,
    BETA_SECTIONS,
    FIXTURE_CORPUS_VERSION,
    build_corpus,
)

FAILURE_EXIT_CODE = 2


def run_validate(workspace, dataset_path, extra=None):
    argv = ["--dataset", dataset_path, "--corpus-manifest", workspace.manifest_path]
    if extra:
        argv.extend(extra)
    return validate.main(argv)


def contains(problems, fragment):
    return [problem for problem in problems if fragment in problem]


# ---------------------------------------------------------------------------
# 夹具自检
# ---------------------------------------------------------------------------

def test_fixture_corpus_sections_match_expected_spans(workspace):
    sections = tuple((s["startLine"], s["endLine"]) for s in workspace.snapshot.sections("alpha"))
    assert sections == ALPHA_SECTIONS
    assert tuple((s["startLine"], s["endLine"]) for s in workspace.snapshot.sections("beta")) == BETA_SECTIONS
    assert workspace.snapshot.section_count() == len(ALPHA_SECTIONS) + len(BETA_SECTIONS)


def test_valid_fixture_dataset_passes_cli(workspace, capsys):
    path = workspace.write_dataset([workspace.question(), workspace.unanswerable()])
    assert run_validate(workspace, path) == 0
    out = capsys.readouterr().out
    assert "校验通过" in out


def test_missing_corpus_file_fails_validation(tmpdir):
    root = str(tmpdir.mkdir("broken"))
    manifest_path = build_corpus(root)
    os.remove(os.path.join(root, "corpus", "docs", "doc_alpha.md"))
    from exo_common import EvalError
    from exo_corpus import CorpusSnapshot

    with pytest.raises(EvalError) as error:
        CorpusSnapshot(manifest_path)
    assert "语料快照校验失败" in str(error.value)


def test_dataset_file_missing_returns_environment_error(workspace):
    assert run_validate(workspace, os.path.join(workspace.root, "not-there.jsonl")) == 3


# ---------------------------------------------------------------------------
# 失败路径：单元级
# ---------------------------------------------------------------------------

def test_duplicate_id_fails(workspace):
    first = workspace.question()
    second = workspace.question(
        id="fx-001", split="test", groupId="g-beta-version", question="Beta 的版本说法是什么？",
        relevantEvidence=[workspace.evidence("beta", 7, 7, ["Beta 声称使用 Java 17"])],
    )
    problems = workspace.problems([first, second])
    assert contains(problems, "id 重复")
    assert len(problems) == 1


def test_line_numbers_beyond_document_fail(workspace):
    row = workspace.question(
        relevantEvidence=[workspace.evidence("alpha", ALPHA_VERSION_LINE, 99, ["Alpha 使用 Java 21"])],
    )
    problems = workspace.problems([row])
    assert contains(problems, "行号非法")
    assert contains(problems, "startLine=7 endLine=99")


def test_reversed_line_range_fails(workspace):
    row = workspace.question(
        relevantEvidence=[workspace.evidence("alpha", ALPHA_RETRIEVAL_LINE, ALPHA_VERSION_LINE, ["Alpha"])],
    )
    assert contains(workspace.problems([row]), "行号非法")


def test_zero_start_line_fails(workspace):
    row = workspace.question(relevantEvidence=[workspace.evidence("alpha", 0, 3, ["Alpha 使用 Java 21"])])
    assert contains(workspace.problems([row]), "行号非法")


def test_hash_drift_fails(workspace):
    unit = workspace.evidence("alpha", ALPHA_VERSION_LINE, ALPHA_VERSION_LINE, ["Alpha 使用 Java 21"])
    unit["sourceHash"] = "0" * 64
    problems = workspace.problems([workspace.question(relevantEvidence=[unit])])
    assert contains(problems, "sourceHash 与 manifest 不符（hash 漂移）")


def test_unknown_doc_key_fails(workspace):
    unit = workspace.evidence("alpha", ALPHA_VERSION_LINE, ALPHA_VERSION_LINE, ["Alpha 使用 Java 21"])
    unit["docKey"] = "gamma"
    problems = workspace.problems([workspace.question(relevantEvidence=[unit])])
    assert contains(problems, "docKey 不在语料快照中")


def test_missing_gold_fails(workspace):
    unit = workspace.evidence("beta", 7, 7, ["Beta 声称使用 Java 17"], role="distractor")
    problems = workspace.problems([workspace.question(relevantEvidence=[unit])])
    assert contains(problems, "缺少 required 证据（缺少 gold）")


def test_answerable_without_any_evidence_fails(workspace):
    problems = workspace.problems([workspace.question(relevantEvidence=[])])
    assert contains(problems, "缺少 required 证据（缺少 gold）")


def test_unanswerable_with_required_evidence_fails(workspace):
    row = workspace.unanswerable(
        relevantEvidence=[workspace.evidence("alpha", ALPHA_VERSION_LINE, ALPHA_VERSION_LINE, ["Alpha 使用 Java 21"])],
    )
    assert contains(workspace.problems([row]), "标注自相矛盾")


def test_fact_outside_evidence_span_fails(workspace):
    row = workspace.question(
        relevantEvidence=[workspace.evidence("alpha", ALPHA_VERSION_LINE, ALPHA_VERSION_LINE,
                                             ["Alpha 使用 Java 17"])],
    )
    assert contains(workspace.problems([row]), "事实未出现在证据行范围内")


def test_group_crossing_splits_fails(workspace):
    dev_row = workspace.question()
    test_row = workspace.question(
        id="fx-002", split="test", question="Alpha 的向量维度是 1024 维吗？",
        relevantEvidence=[workspace.evidence("alpha", ALPHA_RETRIEVAL_LINE, ALPHA_RETRIEVAL_LINE,
                                             ["Alpha 按 L2 距离排序"])],
    )
    problems = workspace.problems([dev_row, test_row])
    assert contains(problems, "（split 重叠）")
    assert len(problems) == 1


def test_same_question_text_in_two_splits_fails(workspace):
    dev_row = workspace.question()
    test_row = workspace.question(
        id="fx-003", split="test", groupId="g-other", category="body-fact",
        relevantEvidence=[workspace.evidence("alpha", ALPHA_RETRIEVAL_LINE, ALPHA_RETRIEVAL_LINE,
                                             ["Alpha 按 L2 距离排序"])],
    )
    assert contains(workspace.problems([dev_row, test_row]), "完全相同的问题文本出现在不同 split")


def test_same_required_signature_in_other_group_fails(workspace):
    first = workspace.question()
    second = workspace.question(id="fx-004", groupId="g-other", category="paraphrase-zh",
                                question="Alpha 的 Java 版本是哪个？")
    problems = workspace.problems([first, second])
    assert contains(problems, "证据签名完全相同但 groupId 不同")
    assert contains(problems, "g-alpha-version")


def test_corpus_version_mismatch_fails(workspace):
    row = workspace.question(corpusVersion="v0-old")
    assert contains(workspace.problems([row]), "与语料快照 %r 不一致" % FIXTURE_CORPUS_VERSION)


def test_empty_expected_facts_fails(workspace):
    assert contains(workspace.problems([workspace.question(expectedFacts=[])]),
                    "expectedFacts 必须是非空数组")


def test_missing_evidence_facts_fails(workspace):
    unit = workspace.evidence("alpha", ALPHA_VERSION_LINE, ALPHA_VERSION_LINE, ["Alpha 使用 Java 21"])
    unit["facts"] = []
    assert contains(workspace.problems([workspace.question(relevantEvidence=[unit])]),
                    "facts 必须是非空数组")


def test_unknown_category_fails(workspace):
    assert contains(workspace.problems([workspace.question(category="随便写的类别")]), "category 非法")


def test_freeze_sha256_mismatch_fails(workspace):
    from exo_dataset import validate_questions

    path = workspace.write_dataset([workspace.question()])
    freeze = {"datasetSha256": "f" * 64, "testSplitSha256": "e" * 64}
    problems = validate_questions([workspace.question()], workspace.snapshot,
                                  dataset_path=path, freeze=freeze)
    assert contains(problems, "datasetSha256 与冻结记录不符")
    assert contains(problems, "testSplitSha256 与冻结记录不符")


def test_freeze_sha256_match_passes(workspace):
    from exo_common import sha256_file
    from exo_dataset import test_split_fingerprint, validate_questions

    questions = [workspace.question(), workspace.unanswerable()]
    path = workspace.write_dataset(questions)
    freeze = {
        "datasetSha256": sha256_file(path),
        "testSplitSha256": test_split_fingerprint(questions),
    }
    assert validate_questions(questions, workspace.snapshot, dataset_path=path, freeze=freeze) == []


# ---------------------------------------------------------------------------
# 失败路径：命令级（退出码必须是 2）
# ---------------------------------------------------------------------------

MUTATIONS = {
    "duplicate-id": lambda ws: [
        ws.question(),
        ws.question(id="fx-001", split="test", groupId="g-beta-version", question="Beta 的版本说法是什么？",
                    relevantEvidence=[ws.evidence("beta", 7, 7, ["Beta 声称使用 Java 17"])]),
    ],
    "stale-line-number": lambda ws: [
        ws.question(relevantEvidence=[ws.evidence("alpha", ALPHA_VERSION_LINE, 42, ["Alpha 使用 Java 21"])]),
    ],
    "hash-drift": lambda ws: [
        ws.question(relevantEvidence=[dict(ws.evidence("alpha", ALPHA_VERSION_LINE, ALPHA_VERSION_LINE,
                                                       ["Alpha 使用 Java 21"]), sourceHash="a" * 64)]),
    ],
    "missing-gold": lambda ws: [
        ws.question(relevantEvidence=[ws.evidence("beta", 7, 7, ["Beta 声称使用 Java 17"],
                                                  role="distractor")]),
    ],
    "split-overlap": lambda ws: [
        ws.question(),
        ws.question(id="fx-005", split="test", question="Alpha 的检索参数是什么？",
                    relevantEvidence=[ws.evidence("alpha", ALPHA_RETRIEVAL_LINE, ALPHA_RETRIEVAL_LINE,
                                                  ["Alpha 按 L2 距离排序"])]),
    ],
    "fact-outside-span": lambda ws: [
        ws.question(relevantEvidence=[ws.evidence("alpha", ALPHA_RETRIEVAL_LINE, ALPHA_RETRIEVAL_LINE,
                                                  ["Alpha 使用 Java 21"])]),
    ],
}


@pytest.mark.parametrize("name", sorted(MUTATIONS.keys()))
def test_cli_fails_with_exit_code_2(workspace, capsys, name):
    path = workspace.write_dataset(MUTATIONS[name](workspace))
    code = run_validate(workspace, path)
    captured = capsys.readouterr()
    assert code == FAILURE_EXIT_CODE
    assert "校验失败" in captured.out


def test_cli_returns_zero_for_frozen_style_layout(workspace, capsys):
    questions = [workspace.question(), workspace.unanswerable()]
    path = workspace.write_dataset(questions)
    freeze_path = os.path.join(workspace.root, "freeze.json")
    from exo_common import sha256_file, write_json
    from exo_dataset import test_split_fingerprint

    write_json(freeze_path, {
        "datasetSha256": sha256_file(path),
        "testSplitSha256": test_split_fingerprint(questions),
    })
    code = run_validate(workspace, path, extra=["--freeze", freeze_path, "--json"])
    out = capsys.readouterr().out
    assert code == 0
    assert "\"ok\": true" in out
