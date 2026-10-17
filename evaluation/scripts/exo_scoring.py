"""指标计算（纯函数，离线可测）。

命中判据（固定口径，README.md 中同步声明）：
  hit 覆盖证据单元 E，当且仅当
  1) hit.docKey == E.docKey，且两侧 sourceHash 都存在时相等；
  2) hit 的来源行范围完全包含 E 的行范围（startLine <= E.startLine 且 endLine >= E.endLine）；
     仅仅与某一行相交不算命中；
  3) E.facts 中每个事实字符串（空白折叠后）都出现在 hit 返回给模型的文本正文里；
  4) hit.startLine/endLine 缺失或 payload 缺失时，视为"无法核验"，不计命中。

重叠片段去重：每题按"证据单元集合"计分，多个片段覆盖同一证据单元只算一次。
"""

from exo_common import norm_ws, latency_stats

STATUS_OK = "OK"
STATUS_NO_MATCH = "NO_MATCH"
STATUS_UNAVAILABLE = "UNAVAILABLE"


def hit_covers_unit(hit, unit):
    if hit is None or unit is None:
        return False
    if hit.get("docKey") != unit.get("docKey"):
        return False
    hit_hash = hit.get("sourceHash")
    unit_hash = unit.get("sourceHash")
    if hit_hash and unit_hash and hit_hash != unit_hash:
        return False
    start_line = hit.get("startLine")
    end_line = hit.get("endLine")
    if start_line is None or end_line is None:
        return False
    if start_line > unit.get("startLine") or end_line < unit.get("endLine"):
        return False
    payload = hit.get("payload")
    if payload is None:
        return False
    normalized = norm_ws(payload)
    for fact in unit.get("facts") or []:
        if norm_ws(fact) not in normalized:
            return False
    return True


def units_of_role(question, role):
    return [unit for unit in question.get("relevantEvidence") or [] if unit.get("role", "required") == role]


def first_hit_rank(hits, unit):
    for hit in hits:
        if hit_covers_unit(hit, unit):
            return hit.get("rank")
    return None


def score_question(question, result, k):
    """返回单题在 k 截断下的得分明细。"""
    hits = [hit for hit in (result.get("hits") or []) if hit.get("rank") is not None and hit.get("rank") <= k]
    required = units_of_role(question, "required")
    distractor = units_of_role(question, "distractor")
    covered_required = [unit for unit in required if first_hit_rank(hits, unit) is not None]
    covered_docs = sorted(set(unit.get("docKey") for unit in covered_required))
    required_docs = sorted(set(unit.get("docKey") for unit in required))
    ranks = [first_hit_rank(hits, unit) for unit in required]
    ranks = [rank for rank in ranks if rank is not None]
    covered_distractor = [unit for unit in distractor if first_hit_rank(hits, unit) is not None]

    top_rank = min(ranks) if ranks else None
    return {
        "id": question.get("id"),
        "split": question.get("split"),
        "category": question.get("category"),
        "answerable": bool(question.get("answerable")),
        "k": k,
        "status": result.get("status"),
        "hit": bool(covered_required),
        "recall": (len(covered_required) / float(len(required))) if required else 0.0,
        "reciprocalRank": (1.0 / float(top_rank)) if top_rank else 0.0,
        "goldUnits": len(required),
        "goldUnitsCovered": len(covered_required),
        "requiredDocuments": required_docs,
        "coveredDocuments": covered_docs,
        "crossDocumentCoverage": (len(covered_docs) / float(len(required_docs))) if required_docs else 0.0,
        "allRequiredDocumentsCovered": bool(required_docs) and covered_docs == required_docs,
        "distractorUnits": len(distractor),
        "distractorUnitsRetrieved": len(covered_distractor),
    }


def _empty_bucket():
    return {
        "questions": 0,
        "hit": 0,
        "recallSum": 0.0,
        "mrrSum": 0.0,
        "goldUnits": 0,
        "goldUnitsCovered": 0,
        "crossDocSum": 0.0,
        "crossDocQuestions": 0,
        "allDocsCovered": 0,
        "distractorQuestions": 0,
        "distractorRetrievedQuestions": 0,
    }


def _finalize(bucket):
    questions = bucket["questions"]
    cross_questions = bucket["crossDocQuestions"]
    distractor_questions = bucket["distractorQuestions"]
    return {
        "questions": questions,
        "hitAt": (bucket["hit"] / float(questions)) if questions else None,
        "recallAt": (bucket["recallSum"] / float(questions)) if questions else None,
        "mrrAt": (bucket["mrrSum"] / float(questions)) if questions else None,
        "goldUnits": bucket["goldUnits"],
        "goldUnitsCovered": bucket["goldUnitsCovered"],
        "microRecall": (bucket["goldUnitsCovered"] / float(bucket["goldUnits"])) if bucket["goldUnits"] else None,
        "crossDocumentCoverageAt": (bucket["crossDocSum"] / float(cross_questions)) if cross_questions else None,
        "allRequiredDocumentsCoveredRateAt": (
            bucket["allDocsCovered"] / float(cross_questions) if cross_questions else None
        ),
        "crossDocumentQuestions": cross_questions,
        "distractorTopkRate": (
            bucket["distractorRetrievedQuestions"] / float(distractor_questions) if distractor_questions else None
        ),
        "distractorQuestions": distractor_questions,
    }


def aggregate(questions, results_by_id, k_values, split=None, scopes=None):
    """按 k 汇总指标；split 非空时只统计该 split；scopes 用于按语料范围裁剪题集。"""
    metrics = {}
    for k in k_values:
        answerable_bucket = _empty_bucket()
        for question in questions:
            if split and question.get("split") != split:
                continue
            if scopes is not None and question.get("id") not in scopes:
                continue
            result = results_by_id.get(question.get("id")) or {"status": STATUS_UNAVAILABLE, "hits": []}
            detail = score_question(question, result, k)
            if not question.get("answerable"):
                continue
            for bucket in (answerable_bucket,):
                bucket["questions"] += 1
                bucket["goldUnits"] += detail["goldUnits"]
                bucket["goldUnitsCovered"] += detail["goldUnitsCovered"]
                bucket["recallSum"] += detail["recall"]
                bucket["mrrSum"] += detail["reciprocalRank"]
                if detail["hit"]:
                    bucket["hit"] += 1
            if detail["requiredDocuments"]:
                answerable_bucket["crossDocQuestions"] += 1
                answerable_bucket["crossDocSum"] += detail["crossDocumentCoverage"]
                if detail["allRequiredDocumentsCovered"]:
                    answerable_bucket["allDocsCovered"] += 1
            if detail["distractorUnits"]:
                answerable_bucket["distractorQuestions"] += 1
                if detail["distractorUnitsRetrieved"]:
                    answerable_bucket["distractorRetrievedQuestions"] += 1
        metrics["k=%d" % k] = _finalize(answerable_bucket)
    return metrics


def category_breakdown(questions, results_by_id, k, split=None):
    categories = {}
    for question in questions:
        if split and question.get("split") != split:
            continue
        if not question.get("answerable"):
            continue
        category = question.get("category")
        bucket = categories.setdefault(category, _empty_bucket())
        result = results_by_id.get(question.get("id")) or {"status": STATUS_UNAVAILABLE, "hits": []}
        detail = score_question(question, result, k)
        bucket["questions"] += 1
        bucket["goldUnits"] += detail["goldUnits"]
        bucket["goldUnitsCovered"] += detail["goldUnitsCovered"]
        bucket["recallSum"] += detail["recall"]
        bucket["mrrSum"] += detail["reciprocalRank"]
        if detail["hit"]:
            bucket["hit"] += 1
    return dict((key, _finalize(value)) for key, value in categories.items())


def no_answer_stats(questions, results_by_id, split=None):
    """无答案题：检索级拒绝与故障分开统计；故障不算正确拒绝。"""
    total = 0
    rejected = 0
    unavailable = 0
    ok_no_hit = 0
    returned_hits = 0
    for question in questions:
        if question.get("answerable"):
            continue
        if split and question.get("split") != split:
            continue
        total += 1
        result = results_by_id.get(question.get("id")) or {"status": STATUS_UNAVAILABLE, "hits": []}
        status = result.get("status")
        if status == STATUS_UNAVAILABLE:
            unavailable += 1
        elif status == STATUS_NO_MATCH:
            rejected += 1
        else:
            if result.get("hits"):
                returned_hits += 1
            else:
                ok_no_hit += 1
    return {
        "total": total,
        "retrievalRejected": rejected,
        "retrievalRejectionRate": (rejected / float(total)) if total else None,
        "retrievalRejectionRateExcludingFailures": (
            rejected / float(total - unavailable) if (total - unavailable) else None
        ),
        "serviceFailures": unavailable,
        "okButEmpty": ok_no_hit,
        "okWithHits": returned_hits,
        "answerLevelRejection": None,
        "answerLevelNote": "本次运行只调用 Embedding 与向量检索，未调用生成模型：回答级拒绝率未测。",
    }


def failure_summary(questions, results_by_id):
    failures = {"serviceFailures": [], "answerableMisses": [], "unanswerableWithHits": []}
    for question in questions:
        result = results_by_id.get(question.get("id")) or {"status": STATUS_UNAVAILABLE, "hits": []}
        if result.get("status") == STATUS_UNAVAILABLE:
            failures["serviceFailures"].append(
                {"id": question.get("id"), "errorCode": result.get("errorCode"), "message": result.get("message")}
            )
            continue
        if question.get("answerable"):
            detail = score_question(question, result, 3)
            if not detail["hit"]:
                top = (result.get("hits") or [{}])[0]
                failures["answerableMisses"].append(
                    {
                        "id": question.get("id"),
                        "category": question.get("category"),
                        "split": question.get("split"),
                        "question": question.get("question"),
                        "goldUnits": [
                            {
                                "docKey": unit.get("docKey"),
                                "startLine": unit.get("startLine"),
                                "endLine": unit.get("endLine"),
                            }
                            for unit in units_of_role(question, "required")
                        ],
                        "top1": {
                            "docKey": top.get("docKey"),
                            "startLine": top.get("startLine"),
                            "endLine": top.get("endLine"),
                            "headingPath": top.get("headingPath"),
                            "distance": top.get("distance"),
                        },
                    }
                )
        elif result.get("hits"):
            failures["unanswerableWithHits"].append({"id": question.get("id"), "hitCount": len(result.get("hits"))})
    return failures


def distance_separation(questions, results_by_id, split=None):
    """开发集/测试集分组对比 top1 L2 距离分布；用于 03 的阈值校准，不构成策略本身。"""
    groups = {"answerable": [], "unanswerable": []}
    for question in questions:
        if split and question.get("split") != split:
            continue
        result = results_by_id.get(question.get("id")) or {"status": STATUS_UNAVAILABLE, "hits": []}
        hits = result.get("hits") or []
        if not hits:
            continue
        distance = hits[0].get("distance")
        if distance is None:
            continue
        key = "answerable" if question.get("answerable") else "unanswerable"
        groups[key].append(distance)
    return dict((key, latency_stats(values)) for key, values in groups.items())


def latency_report(results):
    retrieval = [row.get("retrievalLatencyMs") for row in results]
    embedding = [row.get("embeddingLatencyMs") for row in results]
    return {"retrieval": latency_stats(retrieval), "embeddingPerQuery": latency_stats(embedding)}


def tie_report(results, tolerance=1e-9):
    """统计返回结果中存在完全相同距离的题目：说明检索排名不可区分。"""
    questions_with_ties = 0
    for row in results:
        hits = row.get("hits") or []
        for index in range(1, len(hits)):
            first = hits[index - 1].get("distance")
            second = hits[index].get("distance")
            if first is not None and second is not None and abs(first - second) <= tolerance:
                questions_with_ties += 1
                break
    return {"questionsWithIdenticalDistances": questions_with_ties, "tolerance": tolerance}


def build_summary(questions, results, k_values, scopes=None, split_scopes=None):
    results_by_id = dict((row.get("id"), row) for row in results)
    summary = {
        "schemaVersion": 1,
        "kValues": list(k_values),
        "questionCount": len(questions),
        "answerableQuestions": len([q for q in questions if q.get("answerable")]),
        "unanswerableQuestions": len([q for q in questions if not q.get("answerable")]),
        "metricsAll": aggregate(questions, results_by_id, k_values, scopes=scopes),
        "metricsDev": aggregate(questions, results_by_id, k_values, split="dev", scopes=scopes),
        "metricsTest": aggregate(questions, results_by_id, k_values, split="test", scopes=scopes),
        "byCategory": category_breakdown(questions, results_by_id, max(k_values)),
        "noAnswer": no_answer_stats(questions, results_by_id),
        "noAnswerTest": no_answer_stats(questions, results_by_id, split="test"),
        "latency": latency_report(results),
        "distanceSeparation": distance_separation(questions, results_by_id),
        "ties": tie_report(results),
        "failures": failure_summary(questions, results_by_id),
    }
    return summary
