# -*- coding: utf-8 -*-
"""冻结资产的离线回归：语料快照、题集、冻结记录与公开基线 section 数一致。

这些断言保护的是「冻结」本身：任何哈希漂移、题集改动或语料替换都会在这里失败，
而不是等到真实运行后才发现基线与数据不匹配。
"""

import io
import os

import pytest

import validate  # noqa: E402

from conftest import EVAL_DIR, REPO_DIR  # noqa: E402

from exo_common import load_json, sha256_file  # noqa: E402
from exo_corpus import CorpusSnapshot  # noqa: E402
from exo_dataset import dataset_stats, load_questions, test_split_fingerprint  # noqa: E402

MANIFEST_PATH = os.path.join(EVAL_DIR, "corpus", "v1-2026-10-14", "manifest.json")
DATASET_PATH = os.path.join(EVAL_DIR, "datasets", "questions-v1.jsonl")
FREEZE_PATH = os.path.join(EVAL_DIR, "datasets", "freeze.json")

PUBLIC_DEMO_DOCS = (
    ("exobrain-overview", "docs/01_exobrain_overview.md", "01_exobrain_overview.md"),
    ("rag-principles", "docs/02_rag_principles.md", "02_rag_principles.md"),
    ("java-backend-faq", "docs/03_java_backend_faq.md", "03_java_backend_faq.md"),
)
KNOWN_PUBLIC_BASELINE_SECTIONS = 18  # 公开基线：3 份文档 18 个 section


@pytest.fixture(scope="module")
def snapshot():
    return CorpusSnapshot(MANIFEST_PATH)


@pytest.fixture(scope="module")
def questions():
    return load_questions(DATASET_PATH)


def test_split_rule_matches_known_public_baseline(snapshot):
    public_sections = 0
    for doc_key, _path, _source in PUBLIC_DEMO_DOCS:
        public_sections += len(snapshot.sections(doc_key))
    assert public_sections == KNOWN_PUBLIC_BASELINE_SECTIONS
    # 合成文档额外贡献 5 个 section（1 个一级标题 + 4 个二级标题）
    assert len(snapshot.sections("eval-synthetic-java-notes")) == 5
    assert snapshot.section_count() == KNOWN_PUBLIC_BASELINE_SECTIONS + 5


def test_manifest_documents_and_roles(snapshot):
    roles = dict((doc["docKey"], doc["role"]) for doc in snapshot.documents)
    assert roles["eval-synthetic-java-notes"] == "synthetic-eval-only"
    assert sorted(set(roles.values())) == ["public-demo", "synthetic-eval-only"]
    for document in snapshot.documents:
        assert len(document["sha256"]) == 64
        assert os.path.isfile(os.path.join(snapshot.root, document["path"]))
    # 题集、答案与说明文档不得进入索引
    indexed_paths = [document["path"] for document in snapshot.documents]
    assert not [path for path in indexed_paths if "question" in path or "dataset" in path]


def test_public_demo_sources_are_byte_identical(snapshot):
    for _doc_key, relative, source_name in PUBLIC_DEMO_DOCS:
        corpus_file = os.path.join(snapshot.root, relative)
        source_file = os.path.join(REPO_DIR, "docs", "public-demo", source_name)
        assert os.path.isfile(source_file)
        assert sha256_file(corpus_file) == sha256_file(source_file), source_name


def test_dataset_statistics_and_split(snapshot, questions):
    stats = dataset_stats(questions)
    assert stats["total"] >= 40
    assert stats["answerable"] >= 30
    assert stats["unanswerable"] >= 10
    assert stats["bySplit"]["dev"] + stats["bySplit"]["test"] == stats["total"]
    # 语义问题组 60/40 左右切分
    dev_ratio = stats["bySplit"]["dev"] / float(stats["total"])
    assert 0.55 <= dev_ratio <= 0.65
    assert stats["requiredEvidenceUnits"] >= stats["answerable"]
    for question in questions:
        assert question["corpusVersion"] == snapshot.version


def test_dataset_passes_validator_with_freeze(snapshot, questions, capsys):
    assert os.path.isfile(FREEZE_PATH)
    code = validate.main([
        "--dataset", DATASET_PATH,
        "--corpus-manifest", MANIFEST_PATH,
        "--freeze", FREEZE_PATH,
        "--json",
    ])
    out = capsys.readouterr().out
    assert code == 0, out
    assert "\"ok\": true" in out


def test_freeze_record_matches_current_files(snapshot, questions):
    freeze = load_json(FREEZE_PATH)
    assert freeze["datasetSha256"] == sha256_file(DATASET_PATH)
    assert freeze["testSplitSha256"] == test_split_fingerprint(questions)
    assert freeze["corpusManifestSha256"] == snapshot.manifest_sha256
    assert freeze["corpusDocumentSha256"] == snapshot.document_sha256()
    assert freeze["questionCount"] == len(questions)


def test_freeze_record_is_lf_json_without_bom():
    with io.open(FREEZE_PATH, "rb") as handle:
        raw = handle.read()
    assert not raw.startswith(b"\xef\xbb\xbf")
    assert b"\r\n" not in raw
