"""题集（JSONL）结构与合法性校验。

校验项（任何一项失败都应让 validate.py 以非零码退出）：
- 字段缺失/类型错误；ID 重复；split / category 取值非法；corpusVersion 与语料快照不一致
- 证据：docKey 不存在、源 hash 与 manifest 不符（漂移）、行号越界或倒置、
  事实字符串为空或不在证据行范围内（失效行号）
- 答案标注：answerable=true 缺少 required 证据（缺少 gold）、
  answerable=false 却带 required 证据
- split 重叠：同一 groupId 跨 split、同义改写证据签名跨 split、完全重复的问题文本
"""

import os

from exo_common import EvalError, canonical_json, load_jsonl, norm_ws, sha256_file, sha256_text
from exo_corpus import fact_in_span

REQUIRED_FIELDS = (
    "id",
    "split",
    "category",
    "question",
    "answerable",
    "corpusVersion",
    "relevantEvidence",
    "expectedFacts",
)

ALLOWED_SPLITS = ("dev", "test")
ALLOWED_CATEGORIES = (
    "title-fact",
    "body-fact",
    "paraphrase-zh",
    "same-title-different-content",
    "cross-document",
    "distractor",
    "insufficient-info",
)
ALLOWED_ROLES = ("required", "distractor", "insufficiency")


def load_questions(path):
    rows = load_jsonl(path)
    if not rows:
        raise EvalError("题集为空: %s" % path)
    return rows


def questions_sha256(path):
    return sha256_file(path)


def test_split_fingerprint(questions):
    subset = [row for row in questions if row.get("split") == "test"]
    subset = sorted(subset, key=lambda row: row.get("id", ""))
    return sha256_text(canonical_json(subset))


def dev_split_fingerprint(questions):
    subset = [row for row in questions if row.get("split") == "dev"]
    subset = sorted(subset, key=lambda row: row.get("id", ""))
    return sha256_text(canonical_json(subset))


def evidence_signature(question):
    units = []
    for unit in question.get("relevantEvidence") or []:
        if unit.get("role", "required") == "required":
            units.append(
                (unit.get("docKey"), unit.get("startLine"), unit.get("endLine"))
            )
    return tuple(sorted(units))


def required_units(question):
    return [unit for unit in question.get("relevantEvidence") or [] if unit.get("role", "required") == "required"]


def validate_questions(questions, snapshot, dataset_path=None, freeze=None, frozen_dataset_sha256=None):
    """返回问题字符串列表（空列表表示通过）。"""
    problems = []
    seen_ids = set()
    signature_groups = {}
    split_by_group = {}
    split_by_question = {}

    for position, question in enumerate(questions, start=1):
        label = question.get("id") or ("第 %d 行" % position)
        if not isinstance(question, dict):
            problems.append("%s: JSONL 行不是对象" % label)
            continue
        for field in REQUIRED_FIELDS:
            if field not in question:
                problems.append("%s: 缺少字段 %s" % (label, field))
        question_id = question.get("id")
        if not isinstance(question_id, str) or not question_id.strip():
            problems.append("%s: id 必须是非空字符串" % label)
        elif question_id in seen_ids:
            problems.append("%s: id 重复" % label)
        else:
            seen_ids.add(question_id)

        if question.get("split") not in ALLOWED_SPLITS:
            problems.append("%s: split 非法: %r" % (label, question.get("split")))
        if question.get("category") not in ALLOWED_CATEGORIES:
            problems.append("%s: category 非法: %r" % (label, question.get("category")))
        if not isinstance(question.get("question"), str) or not question["question"].strip():
            problems.append("%s: question 必须是非空字符串" % label)
        if not isinstance(question.get("answerable"), bool):
            problems.append("%s: answerable 必须是布尔值" % label)
        group_id = question.get("groupId")
        if not isinstance(group_id, str) or not group_id.strip():
            problems.append("%s: 缺少 groupId（语义问题组，用于 split 切分）" % label)

        if snapshot is not None:
            if question.get("corpusVersion") != snapshot.version:
                problems.append(
                    "%s: corpusVersion=%r 与语料快照 %r 不一致"
                    % (label, question.get("corpusVersion"), snapshot.version)
                )

        expected_facts = question.get("expectedFacts")
        if not isinstance(expected_facts, list) or not expected_facts:
            problems.append("%s: expectedFacts 必须是非空数组" % label)
        else:
            for fact in expected_facts:
                if not isinstance(fact, str) or not fact.strip():
                    problems.append("%s: expectedFacts 含空字符串" % label)

        units = question.get("relevantEvidence")
        if not isinstance(units, list):
            problems.append("%s: relevantEvidence 必须是数组" % label)
            units = []
        for index, unit in enumerate(units):
            unit_label = "%s.evidence[%d]" % (label, index)
            if not isinstance(unit, dict):
                problems.append("%s: 证据单元不是对象" % unit_label)
                continue
            role = unit.get("role", "required")
            if role not in ALLOWED_ROLES:
                problems.append("%s: role 非法: %r" % (unit_label, role))
            facts = unit.get("facts")
            if not isinstance(facts, list) or not facts:
                problems.append("%s: facts 必须是非空数组（命中判据依赖事实语句）" % unit_label)
                facts = []
            for fact in facts:
                if not isinstance(fact, str) or not fact.strip():
                    problems.append("%s: facts 含空字符串" % unit_label)
            doc_key = unit.get("docKey")
            start_line = unit.get("startLine")
            end_line = unit.get("endLine")
            source_hash = unit.get("sourceHash")
            if not isinstance(doc_key, str):
                problems.append("%s: docKey 必须是字符串" % unit_label)
                continue
            if snapshot is None:
                continue
            if doc_key not in snapshot.doc_keys():
                problems.append("%s: docKey 不在语料快照中: %r" % (unit_label, doc_key))
                continue
            if source_hash != snapshot.doc(doc_key)["sha256"]:
                problems.append(
                    "%s: sourceHash 与 manifest 不符（hash 漂移）: %r" % (unit_label, source_hash)
                )
            line_count = snapshot.line_count(doc_key)
            if not isinstance(start_line, int) or not isinstance(end_line, int):
                problems.append("%s: startLine/endLine 必须是整数" % unit_label)
                continue
            if start_line < 1 or end_line < start_line or end_line > line_count:
                problems.append(
                    "%s: 行号非法 startLine=%s endLine=%s（文档共 %d 行）"
                    % (unit_label, start_line, end_line, line_count)
                )
                continue
            for fact in facts:
                if isinstance(fact, str) and fact.strip() and not fact_in_span(
                    snapshot, doc_key, start_line, end_line, fact
                ):
                    problems.append(
                        "%s: 事实未出现在证据行范围内: %r" % (unit_label, fact[:60])
                    )

        required = required_units(question)
        if question.get("answerable") is True and not required:
            problems.append("%s: answerable=true 但缺少 required 证据（缺少 gold）" % label)
        if question.get("answerable") is False and required:
            problems.append("%s: answerable=false 却包含 required 证据，标注自相矛盾" % label)

        if question.get("split") in ALLOWED_SPLITS and isinstance(group_id, str):
            previous = split_by_group.setdefault(group_id, question.get("split"))
            if previous != question.get("split"):
                problems.append(
                    "%s: 同一语义问题组 %s 同时出现在 %s 与 %s（split 重叠）"
                    % (label, group_id, previous, question.get("split"))
                )
        normalized_question = norm_ws(question.get("question"))
        if normalized_question:
            previous_split = split_by_question.setdefault(normalized_question, question.get("split"))
            if previous_split != question.get("split"):
                problems.append("%s: 完全相同的问题文本出现在不同 split" % label)
        if required:
            signature = evidence_signature(question)
            group_of_signature = signature_groups.setdefault(signature, group_id)
            if group_of_signature != group_id:
                problems.append(
                    "%s: 与问题组 %s 的证据签名完全相同但 groupId 不同，疑似同义改写跨组"
                    % (label, group_of_signature)
                )

    if freeze is not None and dataset_path is not None:
        actual_dataset = sha256_file(dataset_path)
        actual_test = test_split_fingerprint(questions)
        if freeze.get("datasetSha256") and freeze["datasetSha256"] != actual_dataset:
            problems.append(
                "datasetSha256 与冻结记录不符: freeze=%s actual=%s"
                % (freeze["datasetSha256"], actual_dataset)
            )
        if freeze.get("testSplitSha256") and freeze["testSplitSha256"] != actual_test:
            problems.append(
                "testSplitSha256 与冻结记录不符（保留测试集在调参后被改动）: freeze=%s actual=%s"
                % (freeze["testSplitSha256"], actual_test)
            )
    if frozen_dataset_sha256 is not None and dataset_path is not None:
        if frozen_dataset_sha256 != sha256_file(dataset_path):
            problems.append("题集 SHA-256 与预期不符")

    return problems


def dataset_stats(questions):
    stats = {
        "total": len(questions),
        "answerable": 0,
        "unanswerable": 0,
        "bySplit": {},
        "byCategory": {},
        "bySplitAnswerable": {},
        "requiredEvidenceUnits": 0,
        "documents": {},
    }
    for question in questions:
        split = question.get("split")
        category = question.get("category")
        answerable = bool(question.get("answerable"))
        stats["bySplit"][split] = stats["bySplit"].get(split, 0) + 1
        stats["byCategory"][category] = stats["byCategory"].get(category, 0) + 1
        key = "%s.%s" % (split, "answerable" if answerable else "unanswerable")
        stats["bySplitAnswerable"][key] = stats["bySplitAnswerable"].get(key, 0) + 1
        if answerable:
            stats["answerable"] += 1
        else:
            stats["unanswerable"] += 1
        units = required_units(question)
        stats["requiredEvidenceUnits"] += len(units)
        for unit in units:
            doc_key = unit.get("docKey")
            stats["documents"][doc_key] = stats["documents"].get(doc_key, 0) + 1
    return stats


def group_split_table(questions):
    groups = {}
    for question in questions:
        group_id = question.get("groupId")
        entry = groups.setdefault(
            group_id,
            {
                "groupId": group_id,
                "split": question.get("split"),
                "category": question.get("category"),
                "questionIds": [],
            },
        )
        entry["questionIds"].append(question.get("id"))
    return [groups[key] for key in sorted(groups.keys())]


def load_dataset_with_snapshot(dataset_path, manifest_path, freeze_path=None):
    from exo_corpus import CorpusSnapshot

    snapshot = CorpusSnapshot(manifest_path) if manifest_path else None
    questions = load_questions(dataset_path)
    freeze = None
    if freeze_path:
        from exo_common import load_json

        freeze = load_json(freeze_path)
    problems = validate_questions(questions, snapshot, dataset_path=os.path.abspath(dataset_path), freeze=freeze)
    return questions, snapshot, problems
