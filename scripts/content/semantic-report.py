# -*- coding: utf-8 -*-
"""聚合逐题判定，算出各组通过的题数——合计不手写。

输入：
  --verdicts  逐题判定数据（题号、来源运行、pass、理由）
  --runs-dir  运行 JSON 所在目录（verdicts 里的 runs 是相对这个目录的文件名）

输出：
  分组表（题号、runId、状态、尝试次数、通过/失败、理由）与各组合计。
  runId、状态、尝试次数、字数一律从运行 JSON 读出，不从判定数据里抄。

生成前做以下校验，任一条不成立就**报错退出**（不生成一个缩小或放大的分母）：
  1. 判定的题必须在对应运行里存在；
  2. 每个 (运行, 题号) 只能出现一次；
  3. 判定覆盖的题号集合必须与运行 JSON 里的题号集合**完全一致**（缺题、多题都报错）；
  4. 组内题号数量必须与 groups[...].questionSet 一致；
  5. pass 必须是布尔值（不接受 "true"、1、None）；
  6. runId 必须从最终尝试读到且非空；
  7. 判不通过必须写理由，且非 SUCCEEDED 的题不得判通过。
"""
import argparse
import io
import json
import os


def load_run(path):
    payload = json.load(io.open(path, encoding="utf-8"))
    return {entry["id"]: entry for entry in payload["results"]}, payload


def final_attempt(entry):
    attempts = entry.get("attempts") or []
    return attempts[-1] if attempts else {}


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
    seen = set()
    declared = {label: set() for label in runs}
    for verdict in verdict_data["verdicts"]:
        label = verdict.get("run")
        question_id = verdict.get("id")
        entries = runs.get(label)
        if entries is None:
            problems.append("%s: 未知运行 %s" % (question_id, label))
            continue
        declared[label].add(question_id)
        key = (label, question_id)
        if key in seen:
            problems.append("%s: 在运行 %s 里重复出现（同一题只能计入一次）" % (question_id, label))
            continue
        seen.add(key)
        if not isinstance(verdict.get("pass"), bool):
            problems.append("%s: pass 必须是布尔值，实际是 %r" % (question_id, verdict.get("pass")))
            continue
        entry = entries.get(question_id)
        if entry is None:
            problems.append("%s: 运行 %s 里没有这道题" % (question_id, label))
            continue
        if not verdict.get("reason"):
            problems.append("%s: 判定缺少理由" % question_id)
        if verdict["pass"] and entry.get("status") != "SUCCEEDED":
            problems.append("%s: 终态 %s 不能判通过" % (question_id, entry.get("status")))
        attempt = final_attempt(entry)
        run_id = attempt.get("runId")
        if not run_id:
            problems.append("%s: 最终尝试里没有 runId，无法把判定关联到真实运行" % question_id)
        attempts = entry.get("attempts") or []
        rows.append({
            "id": question_id, "run": label, "runId": run_id,
            "status": entry.get("status"), "attemptCount": entry.get("attemptCount"),
            "firstAttemptStatus": (attempts[0] if attempts else {}).get("status"),
            "deliveryChannel": entry.get("deliveryChannel"),
            "answerChars": entry.get("answerChars"), "maxChars": entry.get("maxChars"),
            "citations": len(entry.get("citations") or []),
            "pass": verdict["pass"], "reason": verdict.get("reason"),
            "durationMs": attempt.get("durationMs"),
            "elapsedMsTotal": entry.get("elapsedMsTotal"),
        })

    # 完整性：判定集合必须与运行 JSON 的题号集合完全一致，且与 questionSet 声明一致。
    for label, group in verdict_data["groups"].items():
        run_ids = set(runs[label]) if label in runs else set()
        missing = sorted(run_ids - declared[label])
        extra = sorted(declared[label] - run_ids)
        if missing:
            problems.append("运行 %s 缺少判定：%s" % (label, "、".join(missing)))
        if extra:
            problems.append("运行 %s 出现运行里没有的判定：%s" % (label, "、".join(extra)))
        expected = group.get("questionSet")
        if expected is None:
            problems.append("组 %s 没有声明 questionSet，无法校验分母" % label)
        elif len(run_ids) != expected or len(declared[label]) != expected:
            problems.append("组 %s 的题数不一致：运行 %d 题、判定 %d 题、声明 %s 题" % (
                label, len(run_ids), len(declared[label]), expected))
    if problems:
        raise SystemExit("判定数据与运行证据不一致：\n - " + "\n - ".join(problems))

    lines = []
    lines.append("# 逐题判定与分组合计（由 verdicts 数据生成，合计不手写）")
    lines.append("")
    lines.append("判定规则：%s" % verdict_data["rule"])
    lines.append("")
    lines.append("数据来源：`%s`。生成前已校验：每题判定唯一、判定集合与该运行的题号集合完全一致、"
                 "runId 非空、pass 为布尔值、非 SUCCEEDED 不判通过。" % os.path.basename(args.verdicts))
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
        # 首次尝试成功数按运行里真实的首次尝试终态算，不看人工语义判定。
        first_ok = sum(1 for row in group_rows if row["firstAttemptStatus"] == "SUCCEEDED")
        lines.append("")
        lines.append("运行事实：%s 题（成功 %s / 失败 %s）；尝试 %s 次（成功 %s / 失败 %s）；"
                     "模型调用 %s 次、失败 %s 次；守卫拒绝 %s 次；**首次尝试即成功 %s 题**。" % (
                         summary.get("total"), summary.get("succeeded"), summary.get("failed"),
                         summary.get("attemptsTotal"), summary.get("attemptsSucceeded"),
                         summary.get("attemptsFailed"), summary.get("modelCallsTotal"),
                         summary.get("modelCallsFailed"), summary.get("attemptGuardRejections"),
                         first_ok))
        lines.append("")
        lines.append("**内容判定通过 %d / %d**（首次尝试即成功 %d 题；两者口径不同，不互相替代）。" % (
            len(passed), len(group_rows), first_ok))
        lines.append("")
        lines.append("| 题号 | runId | 终态 | 首次尝试 | 尝试 | 通道 | 字数/预算 | 引用 | 判定 | 理由 |")
        lines.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
        for row in group_rows:
            lines.append("| %s | `%s` | %s | %s | %s | %s | %s/%s | %s | %s | %s |" % (
                row["id"], row["runId"], row["status"], row["firstAttemptStatus"],
                row["attemptCount"], row["deliveryChannel"] or "—",
                row["answerChars"], row["maxChars"], row["citations"],
                "通过" if row["pass"] else "**不通过**", row["reason"]))
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
    lines.append("| 数据组 | 内容通过 | 用途 |")
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
