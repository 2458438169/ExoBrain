# -*- coding: utf-8 -*-
"""Aggregate sanitized model-failure evidence from an application log.

模型调用失败在应用里被统一压成 502「语言模型服务暂不可用」，根因只能从日志的两类脱敏信号读出来：

    Language model HTTP request failed: status=<code>   # 供应商返回了 HTTP 错误状态
    Language model call failed: type=<Exception> message=<已脱敏的短消息>

计数口径（上一版在这里少算过）：
- 一次 HTTP 错误会同时留下两行（状态码 + 外层包装异常），不能把两行相加；
- 一次超时/解析错误只会留下 `call failed` 一行；
- 所以失败总数取两者较大值，并分别给出明细，而不是 `sum(statuses) or sum(types)`。

日志可能是 PowerShell `*>` 写出的 UTF-16LE，所以按 BOM 选择解码方式。
本脚本只输出计数、状态码、异常类与已脱敏的短消息，不输出 URL、密钥或供应商响应体；
`--excerpt-out` 会写出命中行原文（同样已脱敏）与日志 sha256、时间范围，便于独立重放归因。
"""
import argparse
import collections
import hashlib
import io
import json
import re

HTTP_STATUS = re.compile(r"Language model HTTP request failed: status=(\d+)")
CALL_FAILED = re.compile(r"Language model call failed: type=([\w.$]+) message=(.*)$")
GUARD = re.compile(r"InvalidAnswerFormatException: (.*)$")
TIMESTAMP = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})")


def detect_encoding(raw):
    if raw.startswith(b"\xff\xfe"):
        return "utf-16-le"          # PowerShell 的 *> 默认写 UTF-16LE
    if raw.startswith(b"\xfe\xff"):
        return "utf-16-be"
    if raw.startswith(b"\xef\xbb\xbf"):
        return "utf-8-sig"
    return "utf-8"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", required=True)
    parser.add_argument("--out", default="")
    parser.add_argument("--excerpt-out", default="", help="写出脱敏的命中行原文与日志摘要")
    args = parser.parse_args()

    with io.open(args.log, "rb") as handle:
        raw = handle.read()
    digest = hashlib.sha256(raw).hexdigest()
    encoding = detect_encoding(raw)

    statuses = collections.Counter()
    types = collections.Counter()
    messages = collections.Counter()
    guard = collections.Counter()
    excerpt = []
    first = last = None
    total_lines = 0

    for line in io.TextIOWrapper(io.BytesIO(raw), encoding=encoding, errors="replace"):
        total_lines += 1
        stamp = TIMESTAMP.match(line)
        if stamp:
            if first is None:
                first = stamp.group(1)
            last = stamp.group(1)
        hit = False
        found = HTTP_STATUS.search(line)
        if found:
            statuses[found.group(1)] += 1
            hit = True
        found = CALL_FAILED.search(line)
        if found:
            types[found.group(1)] += 1
            messages[found.group(2).strip()[:160]] += 1
            hit = True
        found = GUARD.search(line)
        if found:
            guard[found.group(1).strip()] += 1
            hit = True
        if hit:
            excerpt.append(line.rstrip("\r\n"))

    http_failures = sum(statuses.values())
    call_failures = sum(types.values())
    evidence = {
        "logFile": args.log,
        "logEncoding": encoding,
        "logSha256": digest,
        "logLines": total_lines,
        "timeRange": {"first": first, "last": last},
        "httpStatuses": dict(statuses),
        "exceptionTypes": dict(types),
        "exceptionMessages": dict(messages),
        "guardRejections": dict(guard),
        "httpFailureCount": http_failures,
        "wrappedCallFailureCount": call_failures,
        "modelFailureTotal": max(http_failures, call_failures),
        "countingRule": "max(httpFailureCount, wrappedCallFailureCount)：HTTP 错误两行计一次，非 HTTP 错误只有一行",
    }
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    if args.out:
        with io.open(args.out, "w", encoding="utf-8") as handle:
            json.dump(evidence, handle, ensure_ascii=False, indent=2)
    if args.excerpt_out:
        with io.open(args.excerpt_out, "w", encoding="utf-8") as handle:
            handle.write(u"# 脱敏失败日志摘录\n\n")
            handle.write(u"- 来源日志：`%s`\n" % args.log)
            handle.write(u"- 日志 sha256：`%s`\n" % digest)
            handle.write(u"- 日志编码：`%s`\n" % encoding)
            handle.write(u"- 时间范围：%s — %s\n" % (first, last))
            handle.write(u"- 命中行数：%d\n\n" % len(excerpt))
            handle.write(u"```\n")
            for line in excerpt:
                handle.write(line + u"\n")
            handle.write(u"```\n")


if __name__ == "__main__":
    main()