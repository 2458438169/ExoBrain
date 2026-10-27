# -*- coding: utf-8 -*-
"""Render the per-question semantic verdict table and derive its totals.

上一轮的总数是手写的，改一处判定就要重算全部数字。现在判定写在
`evidence/ux08/semantic-verdicts-*.json`，合计由本脚本从表里算出来，
并校验题号与运行结果一一对应，避免「表里有、运行里没有」的漂移。
"""
import argparse
import collections
import io
import json
import sys

VERDICTS = ("pass", "fail", "n/a")


def load(path):
    with io.open(path, encoding="utf-8") as handle:
        return json.load(handle)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", required=True)
    parser.add_argument("--verdicts", required=True)
    parser.add_argument("--out", default="")
    args = parser.parse_args()

    run = load(args.run)
    verdicts = load(args.verdicts)
    results = {item["id"]: item for item in run["results"]}
    rows = verdicts["questions"]

    problems = []
    seen = set()
    for row in rows:
        if row["id"] in seen:
            problems.append("duplicate id: %s" % row["id"])
        seen.add(row["id"])
        if row["id"] not in results:
            problems.append("id not in run: %s" % row["id"])
        for key in ("a", "b", "c"):
            if row.get(key) not in VERDICTS:
                problems.append("%s.%s invalid: %r" % (row["id"], key, row.get(key)))
        if results.get(row["id"], {}).get("status") != "SUCCEEDED":
            if row["a"] != "n/a" or row["b"] != "n/a":
                problems.append("%s failed in the run but judged a/b: %s/%s" % (row["id"], row["a"], row["b"]))
    for missing in sorted(set(results) - seen):
        problems.append("run has no verdict: %s" % missing)
    if problems:
        sys.exit("verdict table inconsistent:\n  " + "\n  ".join(problems))

    totals = {key: collections.Counter(row[key] for row in rows) for key in ("a", "b", "c")}
    zero_source = [row["id"] for row in rows if row.get("zeroSource")]
    off_question = [row["id"] for row in rows if row.get("offQuestion")]

    lines = []
    lines.append(u"| 题号 | 运行 | (a) 逐句对应 | (b) 并集可寻 | (c) 合格拒答 | 依据 | 说明 |")
    lines.append(u"| --- | --- | --- | --- | --- | --- | --- |")
    for row in rows:
        item = results[row["id"]]
        evidence = "、".join(row.get("evidence") or []) or "—"
        lines.append(u"| %s | %s | %s | %s | %s | %s | %s |" % (
            row["id"], item["status"], row["a"], row["b"], row["c"], evidence, row.get("note", "")))
    lines.append(u"")
    lines.append(u"合计（分母 30）：")
    lines.append(u"")
    for key, label in (("a", "逐句对应"), ("b", "并集可寻"), ("c", "合格拒答")):
        counter = totals[key]
        lines.append(u"- %s：**pass %d / fail %d / 不适用 %d**" % (
            label, counter["pass"], counter["fail"], counter["n/a"]))
    lines.append(u"- 零来源答案：%d 条（%s）" % (len(zero_source), "、".join(zero_source) or "无"))
    lines.append(u"- 串题：%d 条（%s）" % (len(off_question), "、".join(off_question) or "无"))
    lines.append(u"- 分母说明：题级失败（守卫拒绝或无答案）仍计入 30 题分母，不排除。")
    text = u"\n".join(lines) + u"\n"

    print(text)
    if args.out:
        with io.open(args.out, "w", encoding="utf-8") as handle:
            handle.write(text)


if __name__ == "__main__":
    main()