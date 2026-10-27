# -*- coding: utf-8 -*-
"""Aggregate sanitized model-failure evidence from an application log.

模型调用失败在应用里被统一压成 502「语言模型服务暂不可用」，所以根因只能从日志里
两类脱敏信号读出来：

    Language model HTTP request failed: status=<code>   # 供应商返回了 HTTP 错误状态
    Language model call failed: type=<Exception> message=<已脱敏的短消息>

本脚本只做计数与分组，不输出 URL、密钥或供应商响应体。
"""
import argparse
import collections
import io
import json
import re

HTTP_STATUS = re.compile(r"Language model HTTP request failed: status=(\d+)")
CALL_FAILED = re.compile(r"Language model call failed: type=([\w.$]+) message=(.*)$")
GUARD = re.compile(r"InvalidAnswerFormatException: (.*)$")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", required=True)
    parser.add_argument("--out", default="")
    args = parser.parse_args()

    statuses = collections.Counter()
    types = collections.Counter()
    messages = collections.Counter()
    guard = collections.Counter()
    with io.open(args.log, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            found = HTTP_STATUS.search(line)
            if found:
                statuses[found.group(1)] += 1
            found = CALL_FAILED.search(line)
            if found:
                types[found.group(1)] += 1
                messages[found.group(2).strip()[:160]] += 1
            found = GUARD.search(line)
            if found:
                guard[found.group(1).strip()] += 1

    evidence = {
        "httpStatuses": dict(statuses),
        "exceptionTypes": dict(types),
        "exceptionMessages": dict(messages),
        "guardRejections": dict(guard),
        "modelFailureTotal": sum(statuses.values()) or sum(types.values()),
    }
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    if args.out:
        with io.open(args.out, "w", encoding="utf-8") as handle:
            json.dump(evidence, handle, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()