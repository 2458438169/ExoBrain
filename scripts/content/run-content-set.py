#!/usr/bin/env python3
"""UX-08：用冻结的 30 题跑真实问答，记录正文、引用、耗时与自动可判定的检查项。

    python scripts/content/run-content-set.py --base http://127.0.0.1:18080 \
        --agent <agent-id> --out <file.json>

只做自动可判定的部分（长度、编号一致性、禁用词、运行状态）；语义是否正确由人逐题复核，
脚本不会替人打分。
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
QUESTIONS = os.path.join(REPO, "docs", "development", "visitor-experience", "ux08-question-set.json")
PROMPT = os.path.join(REPO, "docs", "public-demo", "v2-2026-10-19", "agent-system-prompt.md")

state = {"cookie": "", "csrf": ""}


def call(base, method, path, body=None, timeout=180):
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(base + path, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if state["csrf"]:
        request.add_header("X-CSRF-Token", state["csrf"])
    if state["cookie"]:
        request.add_header("Cookie", state["cookie"])
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            for header in response.headers.get_all("Set-Cookie") or []:
                name = header.split("=", 1)[0]
                if "exobrain_session" in name and not header.startswith(name + "=;"):
                    state["cookie"] = header.split(";", 1)[0]
            raw = response.read().decode("utf-8")
            return response.status, (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8")
        try:
            return error.code, json.loads(raw)
        except ValueError:
            return error.code, raw


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:18080")
    parser.add_argument("--agent", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--pause", type=float, default=1.0)
    args = parser.parse_args()
    base = args.base.rstrip("/")

    status, login = call(base, "POST", "/api/auth/login", {"username": "admin", "password": "localdev123"})
    if status != 200:
        sys.exit("login failed: %s" % status)
    state["csrf"] = login["data"]["csrfToken"]

    prompt = open(PROMPT, encoding="utf-8").read()
    status, patched = call(base, "PATCH", "/api/agents/%s" % args.agent, {"systemPrompt": prompt})
    print("agent prompt update:", status)

    status, session = call(base, "POST", "/api/chat-sessions",
                           {"agentId": args.agent, "title": "UX-08 固定题组 %d" % int(time.time())})
    session_id = ((session or {}).get("data") or {}).get("chatSessionId")
    if not session_id:
        sys.exit("cannot create session: %s" % (session,))

    spec = json.load(open(QUESTIONS, encoding="utf-8"))
    results = []
    for item in spec["questions"]:
        started = time.time()
        status, submitted = call(base, "POST", "/api/chat-messages", {
            "agentId": args.agent, "sessionId": session_id, "role": "user",
            "content": item["question"], "idempotencyKey": "ux08-%s-%d" % (item["id"], int(started)),
        })
        run_id = ((submitted or {}).get("data") or {}).get("runId")
        detail = {}
        for _ in range(90):
            time.sleep(2)
            _, run_body = call(base, "GET", "/api/chat-sessions/%s/runs" % session_id)
            runs = ((run_body or {}).get("data") or {}).get("runs") or []
            if runs and all(run.get("status") not in ("QUEUED", "RUNNING") for run in runs):
                break
        if run_id:
            _, run_detail = call(base, "GET", "/api/runs/%s" % run_id)
            detail = ((run_detail or {}).get("data") or {}).get("summary") or {}
        _, messages = call(base, "GET", "/api/chat-messages/session/%s" % session_id)
        answers = [m for m in (((messages or {}).get("data") or {}).get("chatMessages") or [])
                   if m.get("role") == "assistant" and (m.get("content") or "").strip()]
    spec = json.load(open(QUESTIONS, encoding="utf-8"))
    results = []

    def ask(question, key):
        status, submitted = call(base, "POST", "/api/chat-messages", {
            "agentId": args.agent, "sessionId": session_id, "role": "user",
            "content": question, "idempotencyKey": key,
        })
        run_id = ((submitted or {}).get("data") or {}).get("runId")
        detail = {}
        for _ in range(120):
            time.sleep(2)
            _, run_detail = call(base, "GET", "/api/runs/%s" % run_id)
            detail = ((run_detail or {}).get("data") or {}).get("summary") or {}
            if detail.get("status") in ("SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED"):
                break
        _, messages = call(base, "GET", "/api/chat-messages/session/%s" % session_id)
        all_messages = ((messages or {}).get("data") or {}).get("chatMessages") or []
        answer_id = detail.get("answerMessageId")
        answer = next((m for m in all_messages if m.get("id") == answer_id), None)
        if answer is None:
            # 没有答案关联时只认本轮触发消息之后的那条助手消息，绝不回退到上一轮的旧答案。
            answer = {}
        return run_id, detail, answer

    for item in spec["questions"]:
        started = time.time()
        key = "ux08-%s-%d" % (item["id"], int(started))
        run_id, detail, answer = ask(item["question"], key)
        attempts = 1
        while detail.get("status") != "SUCCEEDED" and attempts < 2:
            time.sleep(8)
            attempts += 1
            run_id, detail, answer = ask(item["question"], "%s-r%d" % (key, attempts))
        text = (answer.get("content") or "") if answer else ""
        citations = ((answer or {}).get("metadata") or {}).get("citations") or []
        labels = [c.get("label") for c in citations]
        markers = sorted({int(n) for n in __import__("re").findall(r"\[(\d+)\]", text)})
        missing = [m for m in markers if m not in labels]
        must_not = [pattern for pattern in item.get("mustNot", [])
                    if __import__("re").search(pattern, text)]
        entry = {
            "id": item["id"], "kind": item["kind"], "question": item["question"],
            "expected": item["expected"], "maxChars": item["maxChars"],
            "runId": run_id, "status": detail.get("status"), "errorCode": detail.get("errorCode"),
            "attempts": attempts,
            "durationMs": detail.get("durationMs"), "wallMs": int((time.time() - started) * 1000),
            "answerChars": len(text), "withinBudget": len(text) <= item["maxChars"],
            "citations": [{"label": c.get("label"),
                           "documentName": (c.get("source") or {}).get("documentName"),
                           "startLine": (c.get("source") or {}).get("startLine"),
                           "endLine": (c.get("source") or {}).get("endLine"),
                           "excerpt": (c.get("excerpt") or "")[:160]} for c in citations],
            "markers": markers, "unknownMarkers": missing,
            "mustNotHits": must_not,
            "answer": text,
        }
        results.append(entry)
        print("%s %s chars=%d citations=%d markers=%s unknown=%s%s" % (
            entry["id"], entry["status"], entry["answerChars"], len(citations), markers, missing,
            (" mustNot=%s" % must_not) if must_not else ""))
        time.sleep(args.pause)


    summary = {
        "total": len(results),
        "succeeded": sum(1 for r in results if r["status"] == "SUCCEEDED"),
        "withinBudget": sum(1 for r in results if r["withinBudget"]),
        "withCitations": sum(1 for r in results if r["citations"]),
        "unknownMarkers": sum(1 for r in results if r["unknownMarkers"]),
        "mustNotHits": sum(1 for r in results if r["mustNotHits"]),
        "p50DurationMs": sorted([r["durationMs"] or 0 for r in results])[len(results) // 2],
    }
    payload = {"version": spec["version"], "agentId": args.agent, "sessionId": session_id,
               "promptSha256": __import__("hashlib").sha256(prompt.encode("utf-8")).hexdigest(),
               "ranAt": time.strftime("%Y-%m-%dT%H:%M:%S"), "summary": summary, "results": results}
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    json.dump(payload, open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(json.dumps(summary, ensure_ascii=False))
    print("written", args.out)

if __name__ == "__main__":
    main()
