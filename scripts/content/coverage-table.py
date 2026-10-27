# -*- coding: utf-8 -*-
"""Render the question-to-corpus coverage table, with the actual line at each reference.

表里的「原文首行」由脚本从语料文件直接读出，避免手写行号漂移；
行号越界会直接报错，而不是悄悄跳过。
"""
import argparse
import io
import json
import os
import re
import sys

REF = re.compile(r"^(\d{2}) L(\d+)(?:-(\d+))?$")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--coverage", required=True)
    parser.add_argument("--corpus", required=True)
    parser.add_argument("--out", default="")
    args = parser.parse_args()

    with io.open(args.coverage, encoding="utf-8") as handle:
        data = json.load(handle)

    cache = {}
    problems = []
    lines = [
        u"# 冻结 30 题的资料覆盖（%s）" % data["corpusVersion"],
        u"",
        u"语料目录：`%s`；题单：`%s`。行号指该版本文件内的行。"
        % (data["corpusDir"], data["questionSet"]),
        u"",
        u"| 题号 | 类型 | 期望的关键事实 | 对应原文 | 首行原文（截断） | 覆盖状态 |",
        u"| --- | --- | --- | --- | --- | --- |",
    ]
    for row in data["coverage"]:
        evidence = row.get("evidence") or []
        first_lines = []
        for ref in evidence:
            found = REF.match(ref)
            if not found:
                problems.append("%s: bad ref %r" % (row["id"], ref))
                continue
            doc_key, start, end = found.group(1), int(found.group(2)), int(found.group(3) or found.group(2))
            name = data["documents"].get(doc_key)
            if name is None:
                problems.append("%s: unknown doc %s" % (row["id"], doc_key))
                continue
            if name not in cache:
                with io.open(os.path.join(args.corpus, name), encoding="utf-8") as handle:
                    cache[name] = handle.read().split("\n")
            body = cache[name]
            if start < 1 or end > len(body):
                problems.append("%s: %s L%d-%d out of range (%d lines)" % (row["id"], doc_key, start, end, len(body)))
                continue
            snippet = next((text.strip() for text in body[start - 1:end] if text.strip()), "")
            first_lines.append(u"%s：%s" % (ref, snippet[:48]))
        lines.append(u"| %s | %s | %s | %s | %s | %s |" % (
            row["id"], row["kind"], row["facts"],
            "、".join(evidence) or "—",
            "<br>".join(first_lines) or "—",
            row.get("gap", "none")))

    if problems:
        sys.exit("coverage table inconsistent:\n  " + "\n  ".join(problems))

    text = u"\n".join(lines) + u"\n"
    print(text)
    if args.out:
        with io.open(args.out, "w", encoding="utf-8") as handle:
            handle.write(text)


if __name__ == "__main__":
    main()