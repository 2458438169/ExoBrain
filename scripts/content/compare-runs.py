# -*- coding: utf-8 -*-
"""Compare two run JSONs on the same question ids (before/after for the frozen set).

只做机械对比（状态、长度、引用数、编号一致性、耗时、历史条数），
语义合格与否仍由人工判定，`--notes` 传入的判定文件只用于附加说明。
"""
import argparse
import io
import json

FIELDS = ("status", "errorCode", "answerChars", "elapsedMsTotal")


def load(path):
    with io.open(path, encoding="utf-8") as handle:
        return {item["id"]: item for item in json.load(handle)["results"]}


def describe(item):
    # 历史条数缺失时显示「未记录」，不能默认成 0——那会被误读成「旧版历史为空」。
    history = item.get("dbHistoryUserMessageCount")
    history_text = u"未记录" if history is None else str(history)
    return u"%s / %s / %d 字 / 引用 %d / 未知编号 %d / %.1f s / 数据库历史 %s 条" % (
        item["status"], item.get("errorCode"), item["answerChars"], len(item["citations"]),
        len(item["unknownMarkers"]), item["elapsedMsTotal"] / 1000.0, history_text)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--before", required=True)
    parser.add_argument("--after", required=True)
    parser.add_argument("--ids", required=True, help="逗号分隔的题号")
    parser.add_argument("--before-label", default="旧版")
    parser.add_argument("--after-label", default="新版")
    parser.add_argument("--notes", default="", help="可选：人工判定 JSON（id -> 说明）")
    parser.add_argument("--out", default="")
    args = parser.parse_args()

    before, after = load(args.before), load(args.after)
    notes = json.load(io.open(args.notes, encoding="utf-8")) if args.notes else {}
    ids = [item.strip().upper() for item in args.ids.split(",") if item.strip()]

    lines = [u"| 题号 | %s | %s | 人工判定 |" % (args.before_label, args.after_label),
             u"| --- | --- | --- | --- |"]
    for question_id in ids:
        lines.append(u"| %s | %s | %s | %s |" % (
            question_id,
            describe(before[question_id]) if question_id in before else u"—",
            describe(after[question_id]) if question_id in after else u"—",
            notes.get(question_id, u"")))
    text = u"\n".join(lines) + u"\n"
    print(text)
    if args.out:
        with io.open(args.out, "w", encoding="utf-8") as handle:
            handle.write(text)


if __name__ == "__main__":
    main()