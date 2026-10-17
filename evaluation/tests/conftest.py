# -*- coding: utf-8 -*-
"""离线 pytest 夹具（Python 3.7 + pytest 3.8）。

不访问网络、不访问数据库：夹具语料由 evaluation/tests/fixtures/corpus/ 复制到临时目录，
manifest 的 sha256 在测试运行时重新计算，避免夹具哈希过期。
"""

import copy
import io
import os
import sys

import pytest

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
EVAL_DIR = os.path.dirname(TESTS_DIR)
REPO_DIR = os.path.dirname(EVAL_DIR)
SCRIPTS_DIR = os.path.join(EVAL_DIR, "scripts")
FIXTURE_CORPUS_DIR = os.path.join(TESTS_DIR, "fixtures", "corpus")

if SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, SCRIPTS_DIR)

from exo_common import ensure_dir, sha256_file, write_json, write_jsonl  # noqa: E402
from exo_corpus import CorpusSnapshot  # noqa: E402
from exo_dataset import validate_questions  # noqa: E402

FIXTURE_CORPUS_VERSION = "fixture-v1"
FIXTURE_DOCUMENTS = (
    {"docKey": "alpha", "filename": "doc_alpha.md", "role": "public-demo"},
    {"docKey": "beta", "filename": "doc_beta.md", "role": "public-demo"},
)

# 夹具语料（doc_alpha.md）的分节结果，由 exo_corpus.split_sections 的规则得出：
#   # Alpha 文档        -> 1..3
#   ## Alpha 的部署版本  -> 5..7（正文行 7）
#   ## Alpha 的检索参数  -> 9..11（正文行 11）
ALPHA_SECTIONS = ((1, 3), (5, 7), (9, 11))
BETA_SECTIONS = ((1, 3), (5, 7))
ALPHA_VERSION_LINE = 7
ALPHA_RETRIEVAL_LINE = 11
BETA_VERSION_LINE = 7


def build_corpus(root):
    """把夹具语料写到 root/corpus，并生成 manifest.json（哈希现场计算）。"""
    corpus_dir = os.path.join(root, "corpus")
    docs_dir = ensure_dir(os.path.join(corpus_dir, "docs"))
    documents = []
    for spec in FIXTURE_DOCUMENTS:
        with io.open(os.path.join(FIXTURE_CORPUS_DIR, spec["filename"]), "rb") as handle:
            data = handle.read()
        target = os.path.join(docs_dir, spec["filename"])
        with io.open(target, "wb") as handle:
            handle.write(data)
        documents.append({
            "docKey": spec["docKey"],
            "path": "docs/" + spec["filename"],
            "sha256": sha256_file(target),
            "sourcePath": "evaluation/tests/fixtures/corpus/" + spec["filename"],
            "license": "测试夹具（本仓库自写）",
            "role": spec["role"],
        })
    manifest_path = os.path.join(corpus_dir, "manifest.json")
    write_json(manifest_path, {
        "schemaVersion": 1,
        "corpusVersion": FIXTURE_CORPUS_VERSION,
        "createdFromCommit": "test-fixture",
        "documents": documents,
    })
    return manifest_path


class FixtureWorkspace(object):
    """测试用的临时语料 + 题集工作区。"""

    def __init__(self, root, manifest_path, snapshot):
        self.root = root
        self.manifest_path = manifest_path
        self.snapshot = snapshot
        self.dataset_path = os.path.join(root, "questions.jsonl")

    # -- 构造题集 ------------------------------------------------------------
    def evidence(self, doc_key, start_line, end_line, facts, role="required", **extra):
        unit = {
            "docKey": doc_key,
            "sourceHash": self.snapshot.doc(doc_key)["sha256"],
            "startLine": start_line,
            "endLine": end_line,
            "role": role,
            "facts": list(facts),
        }
        unit.update(extra)
        return unit

    def question(self, **overrides):
        """一个合法可答题；overrides 用于构造各种非法变体。"""
        row = {
            "id": "fx-001",
            "split": "dev",
            "groupId": "g-alpha-version",
            "category": "title-fact",
            "question": "Alpha 使用哪个 Java 版本？向量维度是多少？",
            "answerable": True,
            "corpusVersion": FIXTURE_CORPUS_VERSION,
            "relevantEvidence": [
                self.evidence("alpha", ALPHA_VERSION_LINE, ALPHA_VERSION_LINE,
                              ["Alpha 使用 Java 21"]),
            ],
            "expectedFacts": ["Java 21"],
        }
        row.update(overrides)
        return copy.deepcopy(row)

    def unanswerable(self, **overrides):
        row = {
            "id": "fx-100",
            "split": "test",
            "groupId": "g-alpha-missing",
            "category": "insufficient-info",
            "question": "Alpha 的商业客户数量是多少？",
            "answerable": False,
            "corpusVersion": FIXTURE_CORPUS_VERSION,
            "relevantEvidence": [],
            "expectedFacts": ["资料未提供：客户数量"],
        }
        row.update(overrides)
        return copy.deepcopy(row)

    # -- 校验 ----------------------------------------------------------------
    def problems(self, questions):
        return validate_questions(questions, self.snapshot)

    def write_dataset(self, questions):
        write_jsonl(self.dataset_path, questions)
        return self.dataset_path


@pytest.fixture
def workspace(tmpdir):
    root = str(tmpdir)
    manifest_path = build_corpus(root)
    snapshot = CorpusSnapshot(manifest_path)
    return FixtureWorkspace(root, manifest_path, snapshot)


@pytest.fixture
def base_question(workspace):
    return workspace.question()
