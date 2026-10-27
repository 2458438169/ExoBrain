# -*- coding: utf-8 -*-
"""Run the frozen UX-08 question set against a real backend and keep every attempt.

三件事是这份脚本必须做对的，前两轮验收都在这上面出过问题：

1. 每次尝试都要留证（runId/状态/错误码/耗时/模型调用），重试不能覆盖第一次；
2. 题级时延要用单调时钟把重试与退避一起包住，`wallMs` 只累加请求观察时间，
   所以它不能标成「含退避」；
3. 模型调用量要把 `MODEL_RETRY` 也算进去——生产把第二次及以后的真实请求记成这个类型。
"""
import argparse
import collections
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
QUESTIONS = os.path.join(ROOT, "docs", "development", "visitor-experience", "ux08-question-set.json")
PROMPT = os.path.join(ROOT, "docs", "public-demo", "v2-2026-10-19", "agent-system-prompt.md")
MARKER = re.compile(r"\[(\d+)\]")
SECRET = re.compile(r"(sk-[A-Za-z0-9_\-]{8,}|Bearer\s+[A-Za-z0-9._\-]+)")
URL = re.compile(r"https?://[^\s\"'<>]+")
# 生产把第一次请求记 MODEL_CALL、第二次及以后记 MODEL_RETRY，两者都算真实调用。
MODEL_CALL_TYPES = ("MODEL_CALL", "MODEL_RETRY")

state = {"csrf": None}


def call(base, method, path, payload=None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(base + path, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if state["csrf"]:
        request.add_header("X-CSRF-Token", state["csrf"])
    cookie = state.get("cookie")
    if cookie:
        request.add_header("Cookie", cookie)
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            raw = response.read().decode("utf-8")
            grabbed = response.headers.get_all("Set-Cookie") or []
            if grabbed:
                state["cookie"] = "; ".join(part.split(";")[0] for part in grabbed)
            return response.status, (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8")
        try:
            return error.code, json.loads(raw)
        except ValueError:
            return error.code, raw


def sanitize(text):
    """脱敏：去掉密钥与主机名，只保留错误类别可判断的部分。"""
    if not text:
        return ""
    text = SECRET.sub("<redacted>", text)
    text = URL.sub("<url>", text)
    return text[:200]


def markers_of(text):
    return sorted({int(number) for number in MARKER.findall(text)})


def ask(base, agent_id, session_id, question, key):
    """提交一次问题，返回这一次尝试的完整记录（不覆盖、不合并）。"""
    started = time.monotonic()
    status, submitted = call(base, "POST", "/api/chat-messages", {
        "agentId": agent_id, "sessionId": session_id, "role": "user",
        "content": question, "idempotencyKey": key,
    })
    run_id = ((submitted or {}).get("data") or {}).get("runId")
    detail, events = {}, []
    for _ in range(120):
        time.sleep(2)
        _, run_detail = call(base, "GET", "/api/runs/%s" % run_id)
        body = (run_detail or {}).get("data") or {}
        detail = body.get("summary") or {}
        events = body.get("events") or []
        if detail.get("status") in ("SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED"):
            break
    _, messages = call(base, "GET", "/api/chat-messages/session/%s" % session_id)
    all_messages = ((messages or {}).get("data") or {}).get("chatMessages") or []
    answer_id = detail.get("answerMessageId")
    answer = next((message for message in all_messages if message.get("id") == answer_id), None) or {}
    text = answer.get("content") or ""
    citations = (answer.get("metadata") or {}).get("citations") or []
    model_calls = [event for event in events if event.get("type") in MODEL_CALL_TYPES]
    failures = [event for event in model_calls if (event.get("status") or "").upper() != "SUCCEEDED"]
    return {
        "runId": run_id,
        "submitStatus": status,
        "status": detail.get("status"),
        "errorCode": detail.get("errorCode"),
        "durationMs": detail.get("durationMs"),
        "wallMs": int((time.monotonic() - started) * 1000),
        "traceIncomplete": detail.get("traceIncomplete"),
        "answerMessageId": answer_id,
        "answerChars": len(text),
        "markers": markers_of(text),
        "modelCalls": len(model_calls),
        "modelRetries": sum(1 for event in model_calls if event.get("type") == "MODEL_RETRY"),
        "modelCallFailures": len(failures),
        "modelCallErrorCodes": sorted({event.get("errorCode") for event in failures if event.get("errorCode")}),
        "modelCallDetails": [sanitize(event.get("detail")) for event in failures][:3],
        "eventTypes": dict(collections.Counter(event.get("type") for event in events)),
        "citations": [{"label": citation.get("label"),
                       "documentName": (citation.get("source") or {}).get("documentName"),
                       "startLine": (citation.get("source") or {}).get("startLine"),
                       "endLine": (citation.get("source") or {}).get("endLine"),
                       "excerpt": (citation.get("excerpt") or "")[:160]} for citation in citations],
        "answer": text,
    }


def percentile(values, fraction):
    if not values:
        return 0
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(round(fraction * (len(ordered) - 1))))
    return ordered[index]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:18080")
    parser.add_argument("--agent", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--pause", type=float, default=1.0)
    parser.add_argument("--attempts", type=int, default=2, help="每题最多尝试几次")
    parser.add_argument("--backoff", type=float, default=8.0, help="重试前的退避秒数")
    parser.add_argument("--only", default="", help="逗号分隔的题号，例如 Q05,Q06；留空跑全部")
    args = parser.parse_args()
    base = args.base.rstrip("/")

    status, login = call(base, "POST", "/api/auth/login", {"username": "admin", "password": "localdev123"})
    if status != 200:
        sys.exit("login failed: %s" % status)
    state["csrf"] = login["data"]["csrfToken"]

    prompt = open(PROMPT, encoding="utf-8").read()
    status, _ = call(base, "PATCH", "/api/agents/%s" % args.agent, {"systemPrompt": prompt})
    print("agent prompt update:", status)

    status, session = call(base, "POST", "/api/chat-sessions",
                           {"agentId": args.agent, "title": "UX-08 固定题组 %d" % int(time.time())})
    session_id = ((session or {}).get("data") or {}).get("chatSessionId")
    if not session_id:
        sys.exit("cannot create session: %s" % (session,))

    spec = json.load(open(QUESTIONS, encoding="utf-8"))
    if args.only:
        wanted = {item.strip().upper() for item in args.only.split(",") if item.strip()}
        spec["questions"] = [question for question in spec["questions"] if question["id"].upper() in wanted]
        print("running subset:", [question["id"] for question in spec["questions"]])

    results = []
    for item in spec["questions"]:
        # 题级单调时钟：把两次尝试之间的退避也包进来。wallMs 不含退避，不能冒充端到端。
        question_started = time.monotonic()
        attempts = []
        for attempt in range(1, max(1, args.attempts) + 1):
            key = "ux08-%s-%d-%d" % (item["id"], int(time.time() * 1000), attempt)
            record = ask(base, args.agent, session_id, item["question"], key)
            record["attempt"] = attempt
            attempts.append(record)
            if record["status"] == "SUCCEEDED":
                break
            if attempt < args.attempts:
                attempts[-1]["backoffMs"] = int(args.backoff * 1000)
                time.sleep(args.backoff)

        final = attempts[-1]
        text = final["answer"]
        labels = [citation["label"] for citation in final["citations"]]
        unknown = [marker for marker in final["markers"] if marker not in labels]
        must_not = [pattern for pattern in item.get("mustNot", []) if re.search(pattern, text)]
        entry = {
            "id": item["id"], "kind": item["kind"], "question": item["question"],
            "expected": item["expected"], "maxChars": item["maxChars"],
            "status": final["status"], "errorCode": final["errorCode"],
            "attemptCount": len(attempts),
            # 端到端：题级单调时钟，含全部尝试与退避。
            "elapsedMsTotal": int((time.monotonic() - question_started) * 1000),
            # 对照：只累加各次请求的观察时间，不含退避。
            "wallMsTotal": sum(record["wallMs"] for record in attempts),
            "attempts": [{key: value for key, value in record.items() if key != "answer"} for record in attempts],
            "answerChars": len(text), "withinBudget": len(text) <= item["maxChars"],
            "citations": final["citations"],
            "markers": final["markers"], "unknownMarkers": unknown,
            "mustNotHits": must_not, "answer": text,
        }
        results.append(entry)
        print("%s %s attempts=%d elapsed=%dms chars=%d citations=%d markers=%s unknown=%s%s" % (
            entry["id"], entry["status"], entry["attemptCount"], entry["elapsedMsTotal"],
            entry["answerChars"], len(entry["citations"]), entry["markers"], unknown,
            (" mustNot=%s" % must_not) if must_not else ""))
        time.sleep(args.pause)

    # 题级结论（每题只算一次）与尝试级结论（每次真实请求都算）分开统计。
    every = [record for entry in results for record in entry["attempts"]]
    ok = [entry for entry in results if entry["status"] == "SUCCEEDED"]
    ok_attempts = [record for record in every if record["status"] == "SUCCEEDED"]
    failed_attempts = [record for record in every if record["status"] != "SUCCEEDED"]
    summary = {
        "total": len(results),
        "succeeded": len(ok),
        "failed": len(results) - len(ok),
        "byStatus": dict(collections.Counter(entry["status"] for entry in results)),
        "byErrorCode": dict(collections.Counter(entry["errorCode"] for entry in results if entry["errorCode"])),
        "finalGuardRejections": sum(1 for entry in results if entry["errorCode"] == "INVALID_ANSWER_FORMAT"),
        "attemptsTotal": len(every),
        "attemptsSucceeded": len(ok_attempts),
        "attemptsFailed": len(failed_attempts),
        "attemptErrorCodes": dict(collections.Counter(record["errorCode"] for record in failed_attempts)),
        "attemptGuardRejections": sum(1 for record in every if record["errorCode"] == "INVALID_ANSWER_FORMAT"),
        "runsTotal": len({record["runId"] for record in every if record["runId"]}),
        "modelCallsTotal": sum(record["modelCalls"] for record in every),
        "modelRetriesTotal": sum(record["modelRetries"] for record in every),
        "modelCallsFailed": sum(record["modelCallFailures"] for record in every),
        "modelCallErrorCodes": dict(collections.Counter(
            code for record in every for code in record["modelCallErrorCodes"])),
        "modelCallDetails": dict(collections.Counter(
            detail for record in every for detail in record["modelCallDetails"] if detail)),
        "traceIncomplete": sum(1 for record in every if record["traceIncomplete"]),
        # 以下只统计成功题：失败题没有正文，混进去会把失败算成「达标」。
        "withinBudget": sum(1 for entry in ok if entry["withinBudget"]),
        "withCitations": sum(1 for entry in ok if entry["citations"]),
        "unknownMarkers": sum(1 for entry in ok if entry["unknownMarkers"]),
        "mustNotHits": sum(1 for entry in ok if entry["mustNotHits"]),
        # 后端耗时：运行时自己的 durationMs。
        "p50DurationMs": percentile([entry["attempts"][-1]["durationMs"] or 0 for entry in ok], 0.50),
        "p95DurationMs": percentile([entry["attempts"][-1]["durationMs"] or 0 for entry in ok], 0.95),
        # 单次请求的观察时间（最后一次尝试），不含退避。
        "p50WallMs": percentile([entry["attempts"][-1]["wallMs"] for entry in ok], 0.50),
        "p95WallMs": percentile([entry["attempts"][-1]["wallMs"] for entry in ok], 0.95),
        # 题级端到端：含全部尝试与退避。
        "p50ElapsedMsTotal": percentile([entry["elapsedMsTotal"] for entry in ok], 0.50),
        "p95ElapsedMsTotal": percentile([entry["elapsedMsTotal"] for entry in ok], 0.95),
        # 对照：各次请求观察时间之和，不含退避（不要把这一项标成含退避）。
        "p50WallMsTotal": percentile([entry["wallMsTotal"] for entry in ok], 0.50),
        "p95WallMsTotal": percentile([entry["wallMsTotal"] for entry in ok], 0.95),
    }
    payload = {"version": spec["version"], "agentId": args.agent, "sessionId": session_id,
               "promptSha256": hashlib.sha256(prompt.encode("utf-8")).hexdigest(),
               "scriptSha256": hashlib.sha256(open(os.path.abspath(__file__), "rb").read()).hexdigest(),
               "runParams": {"pauseSeconds": args.pause, "backoffSeconds": args.backoff,
                             "maxAttempts": args.attempts, "only": args.only},
               "ranAt": time.strftime("%Y-%m-%dT%H:%M:%S"), "summary": summary, "results": results}
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    json.dump(payload, open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(json.dumps(summary, ensure_ascii=False))
    print("written", args.out)


if __name__ == "__main__":
    main()