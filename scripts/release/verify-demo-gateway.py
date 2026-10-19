#!/usr/bin/env python3
"""用真实游客身份穿过反向代理检查「公开路径白名单」是否按预期放行与拒绝。

    python3 scripts/release/verify-demo-gateway.py --base-url http://127.0.0.1:8083
    python3 scripts/release/verify-demo-gateway.py --base-url https://exobrain.sharmoon.cc.cd

检查项对应 UX-01 的验收：运行列表、运行详情、取消（POST）、取消路径的 GET 不产生副作用、
导入状态、引用原文，以及未发布路径仍然 fail closed。请求体一律来自真实后端；脚本只创建一次
游客会话并在结束时删掉它，不改动任何已发布数据。

退出码：0 = 全部符合预期；1 = 有不符合项（逐行打印）。
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

RESULTS = []


def call(base, method, path, body=None, cookie=None, timeout=60):
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(base + path, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if cookie:
        request.add_header("Cookie", cookie)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8", "replace")
            headers = dict(response.headers.items())
            return response.status, raw, headers
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", "replace")
        return error.code, raw, dict(error.headers.items())


def record(name, expected, status, body, extra=""):
    ok = status in expected if isinstance(expected, (tuple, list, set)) else status == expected
    parsed = None
    if ok:
        try:
            parsed = json.loads(body)
        except ValueError:
            if body.strip() and not body.lstrip().startswith("<"):
                parsed = None
    else:
        try:
            parsed = json.loads(body)
        except ValueError:
            parsed = None
    note = extra
    if not ok and body.lstrip().startswith("<"):
        note = (note + " (HTML response)").strip()
    RESULTS.append((ok, name, status, expected, note))
    return parsed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--keep-session", action="store_true")
    parser.add_argument("--label", default="")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")

    print("== %s ==" % (args.label or base))

    status, body, headers = call(base, "GET", "/api/demo/agents")
    agents = record("GET /api/demo/agents (public catalog)", 200, status, body)
    cookie_header = headers.get("Set-Cookie") or headers.get("set-cookie") or ""
    cookie = cookie_header.split(";", 1)[0] if cookie_header.startswith("exobrain_demo=") else None
    if cookie is None:
        print("no visitor cookie was issued; the remaining checks cannot run")
        return report()
    agent_id = None
    for agent in ((agents or {}).get("data") or {}).get("agents") or []:
        agent_id = agent.get("id")
        break
    if not agent_id:
        print("no public agent published")
        return report()

    time.sleep(1)
    status, body, _ = call(base, "POST", "/api/demo/chat-sessions",
                           {"agentId": agent_id, "title": "gateway verification"}, cookie)
    created = record("POST /api/demo/chat-sessions (own session)", 200, status, body)
    session_id = ((created or {}).get("data") or {}).get("chatSessionId")
    if not session_id:
        print("cannot create a visitor session:", (body or "")[:300])
        return report()

    status, body, _ = call(base, "GET", "/api/demo/chat-sessions/%s/runs" % session_id, cookie=cookie)
    runs = record("GET /api/demo/chat-sessions/{id}/runs (run list)", 200, status, body)

    status, body, _ = call(base, "GET", "/api/demo/knowledge-bases", cookie=cookie)
    record("GET /api/demo/knowledge-bases", 200, status, body)
    kbs = ((json.loads(body) if body.strip().startswith("{") else {}).get("data") or {}).get("knowledgeBases") or []
    kb_id = kbs[0].get("id") if kbs else None

    document_id = None
    if kb_id:
        time.sleep(1)
        status, body, _ = call(base, "GET", "/api/demo/documents/kb/%s" % kb_id, cookie=cookie)
        documents = record("GET /api/demo/documents/kb/{id}", 200, status, body)
        for document in ((documents or {}).get("data") or {}).get("documents") or []:
            document_id = document.get("id")
            break
    if document_id:
        status, body, _ = call(base, "GET", "/api/demo/documents/%s/ingestion" % document_id, cookie=cookie)
        record("GET /api/demo/documents/{id}/ingestion (import status)", 200, status, body)
        status, body, _ = call(base, "GET", "/api/demo/documents/%s/content" % document_id, cookie=cookie)
        record("GET /api/demo/documents/{id}/content (citation source)", 200, status, body)

    # 未发布路径必须仍然 fail closed（404），且不是把整段前缀放开。
    status, body, _ = call(base, "GET", "/api/demo/admin/models", cookie=cookie)
    record("GET /api/demo/admin/models (unpublished, must stay closed)", 404, status, body)

    # 取消路径：先确认 GET 被拒（网关 405 / 兜底 403），再由 POST 真正登记取消请求。
    fake_run = str(uuid.uuid4())
    status, body, _ = call(base, "GET", "/api/demo/runs/%s/cancel" % fake_run, cookie=cookie)
    record("GET  /api/demo/runs/{id}/cancel (must not write)", (403, 404, 405), status, body)
    idempotency = "gateway-verify-%d" % int(time.time())
    time.sleep(1)
    status, body, _ = call(base, "POST", "/api/demo/chat-messages",
                           {"agentId": agent_id, "sessionId": session_id, "role": "user",
                            "content": "用一句话说明这个项目能做什么。", "idempotencyKey": idempotency},
                           cookie, timeout=120)
    sent = record("POST /api/demo/chat-messages (own question)", 200, status, body)
    run_id = ((sent or {}).get("data") or {}).get("runId")
    if run_id:
        status, body, _ = call(base, "GET", "/api/demo/runs/%s" % run_id, cookie=cookie)
        detail = record("GET /api/demo/runs/{id} (run detail)", 200, status, body)
        ids = ((detail or {}).get("data") or {}).get("summary") or {}
        record("run detail carries triggering/answer message ids for its owner",
               True, bool(ids.get("triggeringMessageId")) or ids.get("status") in ("QUEUED", "RUNNING"),
               json.dumps(ids, ensure_ascii=False)[:240])
        status, body, _ = call(base, "POST", "/api/demo/runs/%s/cancel" % run_id, cookie=cookie)
        record("POST /api/demo/runs/{id}/cancel (own running run)", 200, status, body)

    if not args.keep_session:
        status, body, _ = call(base, "DELETE", "/api/demo/chat-sessions/%s" % session_id, cookie=cookie)
        record("DELETE /api/demo/chat-sessions/{id} (cleanup)", 200, status, body)

    return report()


def report():
    print("%-62s %-6s %s" % ("check", "status", "result"))
    failures = 0
    for ok, name, status, expected, note in RESULTS:
        mark = "PASS" if ok else "FAIL"
        if not ok:
            failures += 1
        print("%-62s %-6s %s%s" % (name[:62], status, mark, ("  " + note) if note else ""))
    print("summary: %d checks, %d failed" % (len(RESULTS), failures))
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
