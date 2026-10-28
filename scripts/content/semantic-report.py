# -*- coding: utf-8 -*-
"""聚合逐题判定，算出各组通过的题数——合计不手写。

输入：
  --verdicts  逐题判定数据（题号、来源运行、pass、理由）
  --runs-dir  运行 JSON 所在目录（verdicts 里的 runs 是相对这个目录的文件名）

输出：
  分组表（题号、来源运行、runId、状态、尝试次数、通过/失败、理由）与各组合计，
  runId/状态/尝试次数一律从运行 JSON 读出，不从判定数据里抄。

同时做三条一致性校验，任一条不成立就报错退出（不静默算出好看的数）：
  1. 判定的题目必须在对应运行里存在；
  2. 非 SUCCEEDED 的题目不得判通过；
  3. 判不通过必须写理由。
"""
import argparse
import io
import json
import os


def load_run(path):
    payload = json.load(io.open(path, encoding="utf-8"))
    return {entry["id"]: entry for entry in payload["results"]}, payload


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--verdicts", required=True)
    parser.add_argument("--runs-dir", default="")
    parser.add_argument("--out", required=True, help="输出 markdown 表")
    args = parser.parse_args()

    verdict_data = json.load(io.open(args.verdicts, encoding="utf-8"))
    runs_dir = args.runs_dir or os.path.dirname(os.path.abspath(args.verdicts))

    runs, run_meta = {}, {}
    for label, filename in verdict_data["runs"].items():
        entries, payload = load_run(os.path.join(runs_dir, filename))
        runs[label] = entries
        run_meta[label] = {"file": filename, "params": payload.get("runParams") or {},
                           "summary": payload.get("summary") or {},
                           "promptSha256": payload.get("promptSha256")}

    problems, rows = [], []
    for verdict in verdict_data["verdicts"]:
        label = verdict["run"]
        entries = runs.get(label)
        if entries is None:
            problems.append("%s: 未知运行 %s" % (verdict["id"], label))
            continue
        entry = entries.get(verdict["id"])
        if entry is None:
            problems.append("%s: 运行 %s 里没有这道题" % (verdict["id"], label))
            continue
        if not verdict.get("reason"):
            problems.append("%s: 判定缺少理由" % verdict["id"])
        succeeded = entry.get("status") == "SUCCEEDED"
        if verdict["pass"] and not succeeded:
            problems.append("%s: 终态 %s 不能判通过" % (verdict["id"], entry.get("status")))
        attempts = entry.get("attempts") or []
        rows.append({
            "id": verdict["id"], "run": label, "runId": entry.get("runId"),
            "status": entry.get("status"), "attemptCount": entry.get("attemptCount"),
            "deliveryChannel": entry.get("deliveryChannel"),
            "answerChars": entry.get("answerChars"), "maxChars": entry.get("maxChars"),
            "citations": len(entry.get("citations") or []),
            "pass": bool(verdict["pass"]), "reason": verdict.get("reason"),
            "durationMs": (attempts[-1] if attempts else {}).get("durationMs"),
            "elapsedMsTotal": entry.get("elapsedMsTotal"),
        })
    if problems:
        raise SystemExit("判定数据与运行证据不一致：\n - " + "\n - ".join(problems))

    lines = []
    lines.append("# 逐题判定与分组合计（由 verdicts 数据生成，合计不手写）")
    lines.append("")
    lines.append("判定规则：%s" % verdict_data["rule"])
    lines.append("")
    lines.append("数据来源：`%s`" % os.path.basename(args.verdicts))
    lines.append("")

    totals = {}
    for label, group in verdict_data["groups"].items():
        group_rows = [row for row in rows if row["run"] == label]
        passed = [row for row in group_rows if row["pass"]]
        totals[label] = (len(passed), len(group_rows), group)
        meta = run_meta[label]
        params = meta["params"]
        lines.append("## %s" % group["label"])
        lines.append("")
        lines.append("用途：%s。运行文件 `%s`；提示词 sha256 `%s`；语料 `%s`；会话模式 `%s`；"
                     "消息窗口 `%s`；题间间隔 %s s；最多尝试 %s 次；退避 %s s。" % (
                         group["use"], meta["file"], (meta["promptSha256"] or "")[:16],
                         params.get("corpusVersion"), params.get("sessionMode"),
                         params.get("messageWindow"), params.get("pauseSeconds"),
                         params.get("maxAttempts"), params.get("backoffSeconds")))
        summary = meta["summary"]
        lines.append("")
        lines.append("运行事实：%s 题（成功 %s / 失败 %s）；尝试 %s 次（成功 %s / 失败 %s）；"
                     "模型调用 %s 次、失败 %s 次；守卫拒绝 %s 次；首次尝试成功 %s 题。" % (
                         summary.get("total"), summary.get("succeeded"), summary.get("failed"),
                         summary.get("attemptsTotal"), summary.get("attemptsSucceeded"),
                         summary.get("attemptsFailed"), summary.get("modelCallsTotal"),
                         summary.get("modelCallsFailed"), summary.get("attemptGuardRejections"),
                         sum(1 for row in group_rows if row["attemptCount"] == 1 and row["pass"])))
        lines.append("")
        lines.append("**通过 %d / %d。**" % (len(passed), len(group_rows)))
        lines.append("")
        lines.append("| 题号 | runId | 终态 | 尝试 | 通道 | 字数/预算 | 引用 | 判定 | 理由 |")
        lines.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
        for row in group_rows:
            lines.append("| %s | `%s` | %s | %s | %s | %s/%s | %s | %s | %s |" % (
                row["id"], row["runId"], row["status"], row["attemptCount"],
                row["deliveryChannel"] or "—", row["answerChars"], row["maxChars"],
                row["citations"], "通过" if row["pass"] else "**不通过**", row["reason"]))
        lines.append("")

    lines.append("## 跨条件诊断（不作整组达标成绩）")
    lines.append("")
    lines.append(verdict_data.get("crossRunNote", ""))
    lines.append("")
    by_question = {}
    for row in rows:
        current = by_question.get(row["id"])
        if current is None or (row["pass"] and not current["pass"]):
            by_question[row["id"]] = row
    ordered = sorted(by_question.values(), key=lambda row: row["id"])
    passed_cross = [row for row in ordered if row["pass"]]
    lines.append("**通过 %d / %d**；未通过：%s。" % (
        len(passed_cross), len(ordered),
        "、".join(row["id"] for row in ordered if not row["pass"]) or "无"))
    lines.append("")
    lines.append("## 合计对照")
    lines.append("")
    lines.append("| 数据组 | 通过 | 用途 |")
    lines.append("| --- | ---: | --- |")
    for label, (passed, total, group) in totals.items():
        lines.append("| %s | **%d/%d** | %s |" % (group["label"], passed, total, group["use"]))
    lines.append("| 跨条件诊断（每题取已通过的一次） | **%d/%d** | 内容诊断，不作整组达标成绩 |" % (
        len(passed_cross), len(ordered)))
    lines.append("")

    with io.open(args.out, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
    print("written", args.out)
    for label, (passed, total, group) in totals.items():
        print("%s: %d/%d" % (label, passed, total))
    print("cross-run: %d/%d" % (len(passed_cross), len(ordered)))


if __name__ == "__main__":
    main()
