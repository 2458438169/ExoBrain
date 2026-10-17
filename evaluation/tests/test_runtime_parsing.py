# -*- coding: utf-8 -*-
"""运行时解析的离线回归：pgvector 行 → 可评分 hit。

背景（真实运行中踩到的缺陷）：chunk_bge_m3.content / metadata 的列类型是 TEXT，
`to_json(column)::text` 会得到双重编码的 JSON，metadata 退化成 {} 后
docKey/startLine/endLine 全为 None，Hit@k 恒为 0。
"""

import json

import pytest

from exo_common import EvalError
from exo_runtime import decode_json_column, hit_from_row, make_strategy

METADATA = {
    "exoEval": True,
    "indexVersion": "legacy-title-v1",
    "indexRevision": "legacy-title-v1/v1-2026-10-14/abc",
    "docKey": "exobrain-overview",
    "sourceHash": "b" * 64,
    "headingPath": ["ExoBrain 技术栈"],
    "startLine": 5,
    "endLine": 9,
}


def row(content, metadata):
    return [
        "chunk-uuid",
        "doc-uuid",
        json.dumps(content, ensure_ascii=False),          # to_json(content)::text
        json.dumps(json.dumps(metadata, ensure_ascii=False), ensure_ascii=False),
        "0.123",
    ]


def test_decode_json_column_handles_double_encoding():
    assert decode_json_column(json.dumps(json.dumps(METADATA, ensure_ascii=False))) == METADATA
    assert decode_json_column(json.dumps({"a": 1})) == {"a": 1}
    assert decode_json_column("") is None
    assert decode_json_column(None) is None
    # 不是合法 JSON 的裸文本原样返回
    assert decode_json_column(json.dumps("纯文本")) == "纯文本"
    assert decode_json_column("not-json") is None


def test_hit_from_row_reads_metadata_and_position():
    hit = hit_from_row(row("章节正文", METADATA), 1, "fallback-version", "fallback-revision")
    assert hit["docKey"] == "exobrain-overview"
    assert hit["sourceHash"] == "b" * 64
    assert hit["startLine"] == 5
    assert hit["endLine"] == 9
    assert hit["headingPath"] == ["ExoBrain 技术栈"]
    assert hit["payload"] == "章节正文"
    assert hit["payloadLength"] == 4
    assert hit["distance"] == pytest.approx(0.123)
    assert hit["rank"] == 1
    assert hit["indexVersion"] == "legacy-title-v1"
    assert hit["indexRevision"] == "legacy-title-v1/v1-2026-10-14/abc"


def test_hit_from_row_tolerates_broken_metadata():
    hit = hit_from_row(["id", "doc", json.dumps("正文"), "not-json", "0.5"],
                       2, "legacy-title-v1", "rev")
    assert hit["payload"] == "正文"
    assert hit["docKey"] is None
    assert hit["startLine"] is None
    assert hit["rank"] == 2


def test_hit_from_row_falls_back_to_strategy_versions():
    metadata = {"docKey": "alpha"}
    hit = hit_from_row(row("正文", metadata), 3, "legacy-title-v1", "rev-1")
    assert hit["indexVersion"] == "legacy-title-v1"
    assert hit["indexRevision"] == "rev-1"


def test_make_strategy_rejects_unknown_variant():
    with pytest.raises(EvalError) as error:
        make_strategy("unknown-variant", None, None, None, {})
    assert "未知 variant" in str(error.value)


def test_make_strategy_uses_variant_topk(monkeypatch):
    config = {"run": {"topK": 3}, "variants": {"body-v1": {"topK": 5, "kbId": "kb-uuid"}}}
    strategy = make_strategy("body-v1", None, None, None, config)
    assert strategy.top_k == 5
    assert strategy.kb_id == "kb-uuid"
