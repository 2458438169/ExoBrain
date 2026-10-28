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
# 默认用最新语料版本的提示词；旧版本保留在各自目录里，用 --prompt 指定即可复现历史成绩。
PROMPT = os.path.join(ROOT, "docs", "public-demo", "v3-2026-10-28", "agent-system-prompt.md")
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


def call_name(call):
    """Spring AI 的 ToolCall 序列化是 {id,type,name,arguments}；兼容 OpenAI 风格的 function.name。"""
    if not isinstance(call, dict):
        return None
    if call.get("name"):
        return call.get("name")
    function = call.get("function")
    return function.get("name") if isinstance(function, dict) else None


def call_arguments(call):
    if not isinstance(call, dict):
        return ""
    if call.get("arguments"):
        return call.get("arguments")
    function = call.get("function")
    if isinstance(function, dict) and function.get("arguments"):
        return function.get("arguments")
    return ""


def delivery_of(answer, citations):
    """逐条导出这一轮真实的交付通道与 grounded 证据。

    模型不写编号，所以「陈述 → 最终编号 → 真实来源行」只能由服务端字段还原：
    陈述里的 citationId 去 citations 里查同一轮生成的 label 与行范围。
    """
    label_by_citation_id = {citation["citationId"]: citation for citation in citations
                            if citation.get("citationId")}
    metadata = (answer or {}).get("metadata") or {}
    calls = metadata.get("toolCalls") or []
    names = [name for name in (call_name(call) for call in calls) if name]
    if "groundedAnswer" in names:
        channel = "groundedAnswer"
    elif "directAnswer" in names:
        channel = "directAnswer"
    elif (answer or {}).get("content"):
        channel = "content-fallback"
    else:
        channel = None

    statements, insufficient = [], None
    for call in calls:
        if call_name(call) != "groundedAnswer":
            continue
        try:
            parsed = json.loads(call_arguments(call) or "{}")
        except ValueError:
            continue
        if parsed.get("insufficient"):
            insufficient = parsed["insufficient"]
            continue
        for statement in parsed.get("statements") or []:
            citation_id = statement.get("citationId")
            citation = label_by_citation_id.get(citation_id) or {}
            statements.append({
                "text": statement.get("text"),
                "citationId": citation_id,
                "evidenceQuote": statement.get("evidenceQuote"),
                "label": citation.get("label"),
                "documentName": citation.get("documentName"),
                "startLine": citation.get("startLine"),
                "endLine": citation.get("endLine"),
            })
    text = (answer or {}).get("content") or ""
    for statement in statements:
        if statement["label"] is not None:
            continue
        # 同一来源被两个 citationId 指向时，展示引用只保留第一条 id，另一条要靠正文里的编号还原。
        needle = (statement.get("text") or "").strip()
        index = text.find(needle) if needle else -1
        if index < 0:
            continue
        matched = re.match(r"\s*\[(\d+)\]", text[index + len(needle):index + len(needle) + 8])
        if matched:
            statement["label"] = int(matched.group(1))
            statement["labelSource"] = "body-marker"
        else:
            statement["labelSource"] = None
    for statement in statements:
        statement.setdefault("labelSource", "citation")
    return {"deliveryChannel": channel, "toolCallNames": names,
            "statements": statements, "insufficient": insufficient}


def sanitize(text):
    """脱敏：去掉密钥与主机名，只保留错误类别可判断的部分。"""
    if not text:
        return ""
    text = SECRET.sub("<redacted>", text)
    text = URL.sub("<url>", text)
    return text[:200]


def markers_of(text):
    return sorted({int(number) for number in MARKER.findall(text)})


def ask(base, agent_id, session_id, question, key, cancel=False, cancel_delay=1.5):
    """提交一次问题，返回这一次尝试的完整记录（不覆盖、不合并）。

    cancel=True 时在提交后 cancel_delay 秒主动取消，用来可控地制造「前题失败」，
    从而验证输入投影在「未答上的旧轮」上的行为。
    """
    started = time.monotonic()
    status, submitted = call(base, "POST", "/api/chat-messages", {
        "agentId": agent_id, "sessionId": session_id, "role": "user",
        "content": question, "idempotencyKey": key,
    })
    run_id = ((submitted or {}).get("data") or {}).get("runId")
    cancel_status = None
    if cancel and run_id:
        time.sleep(cancel_delay)
        cancel_status, _ = call(base, "POST", "/api/runs/%s/cancel" % run_id)
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
    citation_payload = [{"label": citation.get("label"),
                         "citationId": citation.get("citationId"),
                         "chunkId": citation.get("chunkId"),
                         "documentName": (citation.get("source") or {}).get("documentName"),
                         "startLine": (citation.get("source") or {}).get("startLine"),
                         "endLine": (citation.get("source") or {}).get("endLine"),
                         "excerpt": (citation.get("excerpt") or "")[:160]} for citation in citations]
    delivery = delivery_of(answer, citation_payload)
    model_calls = [event for event in events if event.get("type") in MODEL_CALL_TYPES]
    failures = [event for event in model_calls if (event.get("status") or "").upper() != "SUCCEEDED"]
    return {
        "runId": run_id,
        "submitStatus": status,
        "status": detail.get("status"),
        "errorCode": detail.get("errorCode"),
        "cancelStatus": cancel_status,
        "cancelledOnPurpose": bool(cancel),
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
        "citations": citation_payload,
        "delivery": delivery,
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
    parser.add_argument("--pause", type=float, default=1.0, help="题与题之间的等待秒数（不含一题内的模型调用）")
    parser.add_argument("--model-pacing-ms", type=int, default=0,
                        help="应用侧两次真实模型请求的最小间隔（APP_MODELS_MIN_INTERVAL_MS），只用于记录运行条件")
    parser.add_argument("--attempts", type=int, default=2, help="每题最多尝试几次")
    parser.add_argument("--backoff", type=float, default=8.0, help="重试前的退避秒数")
    parser.add_argument("--only", default="", help="逗号分隔的题号，例如 Q05,Q06；留空跑全部")
    parser.add_argument("--prompt", default=PROMPT, help="系统提示词文件；默认最新语料版本")
    parser.add_argument("--session-mode", choices=("shared", "per-question"), default="shared",
                        help="shared：全部题共用一个会话（历史可见）；per-question：每题新建会话")
    parser.add_argument("--label", default="", help="证据标签，写进会话标题与 runParams")
    parser.add_argument("--cancel-after", default="", help="提交后立刻取消这些题号，制造「前题失败」")
    parser.add_argument("--cancel-delay", type=float, default=1.5, help="提交后多少秒取消")
    parser.add_argument("--follow-up", default="",
                        help="在同一会话里追加一个自定义追问（题单之外的文本），用于「失败后重答」场景")
    parser.add_argument("--corpus", default="",
                        help="语料版本目录（含 manifest.json）；版本与逐文档 sha256 写进证据，避免只靠口头说明")
    parser.add_argument("--message-window", type=int, default=0,
                        help="本轮助手配置的 chatOptions.messageLength（这是运行条件，不是默认值）")
    args = parser.parse_args()
    base = args.base.rstrip("/")
    message_window = args.message_window or None
    corpus = None
    if args.corpus:
        manifest = json.load(open(os.path.join(args.corpus, "manifest.json"), encoding="utf-8"))
        corpus = {"version": manifest.get("version"),
                  "dir": os.path.relpath(os.path.abspath(args.corpus), ROOT),
                  "documents": {entry["file"]: entry.get("sha256")
                                for entry in manifest.get("documents", [])}}

    status, login = call(base, "POST", "/api/auth/login", {"username": "admin", "password": "localdev123"})
    if status != 200:
        sys.exit("login failed: %s" % status)
    state["csrf"] = login["data"]["csrfToken"]

    prompt = open(args.prompt, encoding="utf-8").read()
    status, _ = call(base, "PATCH", "/api/agents/%s" % args.agent, {"systemPrompt": prompt})
    print("agent prompt update:", status)

    def create_session():
        _, created = call(base, "POST", "/api/chat-sessions",
                          {"agentId": args.agent,
                           "title": "UX-08 %s %d" % (args.label or "run", int(time.time() * 1000))})
        return ((created or {}).get("data") or {}).get("chatSessionId")

    session_id = create_session() if args.session_mode == "shared" else None
    if args.session_mode == "shared" and not session_id:
        sys.exit("cannot create session")

    spec = json.load(open(QUESTIONS, encoding="utf-8"))
    if args.only:
        wanted = {item.strip().upper() for item in args.only.split(",") if item.strip()}
        spec["questions"] = [question for question in spec["questions"] if question["id"].upper() in wanted]
        print("running subset:", [question["id"] for question in spec["questions"]])
    if args.follow_up:
        spec["questions"] = list(spec["questions"]) + [{
            "id": "FU1", "kind": "follow-up", "question": args.follow_up,
            "expected": ["在同一会话里追问同一个问题"], "maxChars": 1200,
        }]

    cancel_ids = {item.strip().upper() for item in args.cancel_after.split(",") if item.strip()}
    results = []
    for item in spec["questions"]:
        # per-question：每题新建会话，历史不再跨题累积（用于区分「独立问答」）。
        if args.session_mode == "per-question":
            session_id = create_session()
        # 数据库里的历史条数（本轮提交前）。模型真正看到的条数由服务端投影决定，
        # 见应用日志的「model input projection: … projected=」一行，两者不能混用。
        _, history = call(base, "GET", "/api/chat-messages/session/%s" % session_id)
        history_messages = ((history or {}).get("data") or {}).get("chatMessages") or []
        history_users = [message.get("id") for message in history_messages
                         if message.get("role") == "user"]
        # 题级单调时钟：把两次尝试之间的退避也包进来。wallMs 不含退避，不能冒充端到端。
        question_started = time.monotonic()
        attempts = []
        for attempt in range(1, max(1, args.attempts) + 1):
            key = "ux08-%s-%d-%d" % (item["id"], int(time.time() * 1000), attempt)
            record = ask(base, args.agent, session_id, item["question"], key,
                         cancel=item["id"].upper() in cancel_ids, cancel_delay=args.cancel_delay)
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
            "sessionId": session_id,
            # 这是数据库里的用户消息条数，不是模型输入条数（后者见应用日志的投影行）。
            "dbHistoryUserMessageCount": len(history_users),
            "dbHistoryUserMessageIds": history_users,
            # 对照：只累加各次请求的观察时间，不含退避。
            "wallMsTotal": sum(record["wallMs"] for record in attempts),
            "attempts": [{key: value for key, value in record.items() if key != "answer"} for record in attempts],
            "answerChars": len(text), "withinBudget": len(text) <= item["maxChars"],
            "citations": final["citations"],
            "markers": final["markers"], "unknownMarkers": unknown,
            "mustNotHits": must_not, "answer": text,
            # 逐条事实证据：statement → citationId → 最终 label → 真实来源行。
            "deliveryChannel": final["delivery"]["deliveryChannel"],
            "toolCallNames": final["delivery"]["toolCallNames"],
            "statements": final["delivery"]["statements"],
            "insufficient": final["delivery"]["insufficient"],
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
        # 交付通道与资料不足：成功题里按真实工具调用统计，不从正文关键词猜。
        "deliveryChannels": dict(collections.Counter(
            str(entry["deliveryChannel"]) for entry in results)),
        "groundedStatements": sum(len(entry["statements"]) for entry in ok),
        "insufficientAnswers": sum(1 for entry in ok if entry["insufficient"]),
        "statementsWithoutLabel": sum(1 for entry in ok for statement in entry["statements"]
                                      if statement["label"] is None),
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
               "corpus": corpus,
               "runParams": {"pauseSeconds": args.pause, "backoffSeconds": args.backoff,
                             "maxAttempts": args.attempts, "only": args.only,
                             "sessionMode": args.session_mode, "label": args.label,
                             "promptFile": os.path.relpath(args.prompt, ROOT),
                             "messageWindow": message_window,
                             "modelPacingMs": args.model_pacing_ms,
                             "corpusVersion": (corpus or {}).get("version")},
               "ranAt": time.strftime("%Y-%m-%dT%H:%M:%S"), "summary": summary, "results": results}
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    json.dump(payload, open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(json.dumps(summary, ensure_ascii=False))
    print("written", args.out)


if __name__ == "__main__":
    main()