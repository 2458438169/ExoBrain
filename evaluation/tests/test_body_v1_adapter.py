# -*- coding: utf-8 -*-
"""body-v1 适配器的离线回归：活动 revision 过滤与 docKey 解析。

背景：产品元数据只写 `documentName`（原始文件名），不写评测键 `docKey`；同时产品的
检索 WHERE 是「KB + 当前 PUBLISHED revision」，而 `chunk_bge_m3` 里被替换的旧版本行
仍然保留。适配器如果只按 KB 过滤，就会把旧版本的 chunk 也算进 top-k，指标不再是产品行为。
这两个开关都必须可配置、可离线验证，且缺映射时不能静默当成命中。
"""

import json

import pytest

from exo_common import EvalError
from exo_runtime import BodyV1Strategy, document_keys_by_name, make_strategy


class FakeSnapshot(object):
    def __init__(self, documents):
        self.documents = documents
        self.version = "fixture-v1"


class FakeBridge(object):
    def __init__(self, rows):
        self.rows = rows
        self.sql = []
        self.scalar_sql = []

    def query(self, sql):
        self.sql.append(sql)
        return self.rows

    def scalar(self, sql):
        self.scalar_sql.append(sql)
        return len(self.rows)


class FakeEmbedder(object):
    def embed(self, text):
        return {"vectors": [[0.0, 1.0]], "latencyMs": 1.5}


DOCUMENTS = [
    {"docKey": "alpha", "path": "docs/doc_alpha.md"},
    {"docKey": "beta", "path": "docs/doc_beta.md"},
]


def metadata_row(document_name, start=3, end=5):
    metadata = {
        "v": 1,
        "indexVersion": "body-v1",
        "headingPath": ["Alpha"],
        "startLine": start,
        "endLine": end,
        "sourceHash": "a" * 64,
        "documentName": document_name,
    }
    return ["chunk-1", "doc-1", json.dumps("Alpha 使用 Java 21"), json.dumps(metadata), "0.5"]


def test_document_keys_by_name_maps_only_basenames():
    snapshot = FakeSnapshot(DOCUMENTS)
    assert document_keys_by_name(snapshot) == {"doc_alpha.md": "alpha", "doc_beta.md": "beta"}


def test_document_keys_by_name_rejects_ambiguous_basenames():
    snapshot = FakeSnapshot([
        {"docKey": "alpha", "path": "docs/doc_alpha.md"},
        {"docKey": "beta", "path": "archive/doc_alpha.md"},
    ])
    with pytest.raises(EvalError):
        document_keys_by_name(snapshot)


def test_doc_key_is_resolved_from_document_name():
    bridge = FakeBridge([metadata_row("doc_alpha.md")])
    strategy = BodyV1Strategy(
        FakeSnapshot(DOCUMENTS), bridge, FakeEmbedder(),
        kb_id="11111111-1111-1111-1111-111111111111",
        doc_key_by_name=document_keys_by_name(FakeSnapshot(DOCUMENTS)),
    )
    result = strategy.search("Alpha 使用哪个 Java 版本")
    assert result["status"] == "OK"
    assert result["hits"][0]["docKey"] == "alpha"
    assert result["hits"][0].get("scorable") is None


def test_unmapped_document_name_is_not_scorable():
    bridge = FakeBridge([metadata_row("unknown.md")])
    strategy = BodyV1Strategy(
        FakeSnapshot(DOCUMENTS), bridge, FakeEmbedder(),
        kb_id="11111111-1111-1111-1111-111111111111",
        doc_key_by_name={"doc_alpha.md": "alpha"},
    )
    result = strategy.search("Alpha 使用哪个 Java 版本")
    assert result["hits"][0]["docKey"] is None
    assert result["hits"][0]["scorable"] is False


def test_active_revision_filter_is_applied_to_count_and_query():
    bridge = FakeBridge([metadata_row("doc_alpha.md")])
    strategy = BodyV1Strategy(
        FakeSnapshot(DOCUMENTS), bridge, FakeEmbedder(),
        kb_id="11111111-1111-1111-1111-111111111111",
        use_active_revision=True,
    )
    strategy.search("Alpha 使用哪个 Java 版本")
    assert "exobrain_kb_index_state" in bridge.scalar_sql[0]
    assert "revision_id = (SELECT active_revision_id" in bridge.sql[0]


def test_active_revision_filter_is_optional():
    bridge = FakeBridge([metadata_row("doc_alpha.md")])
    strategy = BodyV1Strategy(
        FakeSnapshot(DOCUMENTS), bridge, FakeEmbedder(),
        kb_id="11111111-1111-1111-1111-111111111111",
    )
    strategy.search("Alpha 使用哪个 Java 版本")
    assert "exobrain_kb_index_state" not in bridge.scalar_sql[0]
    assert "revision_id" not in bridge.sql[0]


def test_make_strategy_wires_the_documented_options():
    config = {
        "variants": {
            "body-v1": {
                "topK": 3,
                "useActiveRevision": True,
                "docKeyFromDocumentName": True,
                "kbId": "11111111-1111-1111-1111-111111111111",
            }
        }
    }
    strategy = make_strategy("body-v1", FakeSnapshot(DOCUMENTS), FakeBridge([]), FakeEmbedder(), config)
    described = strategy.describe()
    assert described["useActiveRevision"] is True
    assert described["docKeyResolution"] == "manifest-document-name"
    assert strategy.doc_key_by_name == {"doc_alpha.md": "alpha", "doc_beta.md": "beta"}
