# -*- coding: utf-8 -*-
"""Turn a run-content-set.py JSON into the two evidence views the acceptance asks for.

一、逐题表：题级只计一次（终态）、首次尝试结果、重试后终态、三档耗时、引用与未知编号；
二、逐条事实：statement.text → citationId → 最终 label → 真实来源行 + evidenceQuote，
   资料不足结果单独列，不并进陈述表。

只读运行 JSON，不重新计算「通过/不通过」——语义判定仍由人工对着原文行写。
"""
import argparse
import io
import json
import re


def seconds(milliseconds):
    return "—" if not milliseconds else "%.2f" % (milliseconds / 1000.0)


def resolve_missing_labels(entry):
    """同一来源被两个 citationId 指向时，展示引用只保留第一条 id。

    另一条陈述的编号只能从正文还原（服务端把它渲染成同一个 [n]），这里按陈述原文找尾部编号。
    """
    text = entry.get("answer") or ""
    for statement in entry.get("statements") or []:
        if statement.get("label") is not None:
            continue
        needle = (statement.get("text") or "").strip()
        index = text.find(needle) if needle else -1
        if index < 0:
            continue
        matched = re.match(r"\s*\[(\d+)\]", text[index + len(needle):index + len(needle) + 8])
        if matched:
            statement["label"] = int(matched.group(1))
            statement["labelSource"] = "body-marker"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", required=True, help="run-content-set.py 产出的 JSON")
    parser.add_argument("--md", required=True, help="逐题表输出路径")
    parser.add_argument("--facts", required=True, help="逐条事实输出路径")
    args = parser.parse_args()

    payload = json.load(io.open(args.run, encoding="utf-8"))
    results = payload["results"]
    summary = payload.get("summary") or {}
    params = payload.get("runParams") or {}

    for entry in results:
        resolve_missing_labels(entry)

    lines = []
    lines.append("# 逐题结果（%s）" % payload.get("version"))
    lines.append("")
    lines.append("运行条件：提示词 `%s`（sha256 `%s`）；语料 `%s`；会话模式 `%s`；消息窗口 `%s`；"
                 "最多尝试 %s 次、退避 %s s。" % (
                     params.get("promptFile"), (payload.get("promptSha256") or "")[:16],
                     params.get("corpusVersion"), params.get("sessionMode"),
                     params.get("messageWindow"), params.get("maxAttempts"),
                     params.get("backoffSeconds")))
    lines.append("")
    lines.append("| 题号 | 终态 | 首次尝试 | 尝试次数 | 交付通道 | 字数 | 达标 | 引用 | 陈述 | 未知编号 | "
                 "资料不足 | 后端 durationMs | 题级 elapsedMsTotal |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for entry in results:
        attempts = entry.get("attempts") or []
        first = attempts[0] if attempts else {}
        first_text = first.get("status") or "—"
        if first.get("errorCode"):
            first_text += " / " + first["errorCode"]
        if not entry.get("answerChars"):
            budget = "—"
        else:
            budget = "是" if entry.get("withinBudget") else "否"
        lines.append("| %s | %s%s | %s | %d | %s | %d | %s | %d | %d | %s | %s | %s | %s |" % (
            entry["id"], entry["status"],
            (" / " + entry["errorCode"]) if entry.get("errorCode") else "",
            first_text, entry.get("attemptCount") or 0,
            entry.get("deliveryChannel") or "—",
            entry.get("answerChars") or 0,
            budget,
            len(entry.get("citations") or []),
            len(entry.get("statements") or []),
            ",".join(str(number) for number in (entry.get("unknownMarkers") or [])) or "0",
            "是" if entry.get("insufficient") else "否",
            seconds((attempts[-1] if attempts else {}).get("durationMs")),
            seconds(entry.get("elapsedMsTotal"))))

    first_attempt_ok = sum(1 for entry in results
                           if (entry.get("attempts") or [{}])[0].get("status") == "SUCCEEDED")
    retried_ok = [entry["id"] for entry in results
                  if entry.get("status") == "SUCCEEDED" and (entry.get("attemptCount") or 0) > 1]
    no_label = sum(1 for entry in results for statement in (entry.get("statements") or [])
                   if statement.get("label") is None)

    lines.append("")
    lines.append("## 汇总")
    lines.append("")
    lines.append("- 题级：%s 题，成功 %s、失败 %s；按状态 %s；按错误码 %s。" % (
        summary.get("total"), summary.get("succeeded"), summary.get("failed"),
        summary.get("byStatus"), summary.get("byErrorCode")))
    lines.append("- 首次尝试成功 %d 题；重试后转成功 %d 题 %s。" % (
        first_attempt_ok, len(retried_ok), retried_ok or ""))
    lines.append("- 尝试级：%s 次（成功 %s / 失败 %s，错误码 %s）；运行 %s 条；守卫拒绝 %s 次。" % (
        summary.get("attemptsTotal"), summary.get("attemptsSucceeded"), summary.get("attemptsFailed"),
        summary.get("attemptErrorCodes"), summary.get("runsTotal"), summary.get("attemptGuardRejections")))
    lines.append("- 模型调用：%s 次（其中重试记 %s 次），失败 %s 次 %s。" % (
        summary.get("modelCallsTotal"), summary.get("modelRetriesTotal"),
        summary.get("modelCallsFailed"), summary.get("modelCallErrorCodes")))
    lines.append("- 交付通道：%s；grounded 陈述 %s 条；资料不足结果 %s 条。" % (
        summary.get("deliveryChannels"), summary.get("groundedStatements"),
        summary.get("insufficientAnswers")))
    lines.append("- 陈述编号：按引用卡片直接对上 %d 条，按正文编号还原 %d 条，仍未对上 %d 条。" % (
        sum(1 for entry in results for statement in (entry.get("statements") or [])
            if statement.get("label") is not None and statement.get("labelSource") != "body-marker"),
        sum(1 for entry in results for statement in (entry.get("statements") or [])
            if statement.get("labelSource") == "body-marker"),
        no_label))
    lines.append("- 达标 %s 题 / 带引用 %s 题 / 有未知编号 %s 题 / 命中 mustNot %s 题（只统计成功题）。" % (
        summary.get("withinBudget"), summary.get("withCitations"),
        summary.get("unknownMarkers"), summary.get("mustNotHits")))
    lines.append("- 后端 `durationMs`：p50 %s s / p95 %s s。" % (
        seconds(summary.get("p50DurationMs")), seconds(summary.get("p95DurationMs"))))
    lines.append("- 题级 `elapsedMsTotal`（含重试与退避）：p50 %s s / p95 %s s。" % (
        seconds(summary.get("p50ElapsedMsTotal")), seconds(summary.get("p95ElapsedMsTotal"))))
    lines.append("- 单次请求观察 `wallMs`（不含退避）：p50 %s s / p95 %s s。" % (
        seconds(summary.get("p50WallMs")), seconds(summary.get("p95WallMs"))))
    lines.append("")
    lines.append("> 三档耗时口径不同，不能互相顶替：`durationMs` 是后端运行自己的耗时，"
                 "`wallMs` 只是一次请求的观察时间，`elapsedMsTotal` 才包含重试与退避。")

    with io.open(args.md, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")

    facts = []
    for entry in results:
        facts.append("===== %s %s channel=%s efforts=%s" % (
            entry["id"], entry["status"], entry.get("deliveryChannel"),
            entry.get("attemptCount")))
        if entry.get("insufficient"):
            facts.append("insufficient: %s" % entry["insufficient"])
            facts.append("citations: %d（资料不足结果不挂引用）" % len(entry.get("citations") or []))
            facts.append("")
            continue
        for statement in entry.get("statements") or []:
            facts.append("  text         : %s" % statement.get("text"))
            facts.append("  citationId   : %s" % statement.get("citationId"))
            facts.append("  evidenceQuote: %s" % statement.get("evidenceQuote"))
            facts.append("  -> [%s] %s L%s-%s（%s）" % (
                statement.get("label"), statement.get("documentName"),
                statement.get("startLine"), statement.get("endLine"),
                "正文还原编号" if statement.get("labelSource") == "body-marker" else "引用卡片"))
            facts.append("")
        if not entry.get("statements"):
            facts.append("  （没有 grounded 陈述：%s）" % (entry.get("errorCode") or "无正文"))
            facts.append("")

    with io.open(args.facts, "w", encoding="utf-8") as handle:
        handle.write("\n".join(facts) + "\n")

    print("written", args.md)
    print("written", args.facts)


if __name__ == "__main__":
    main()
