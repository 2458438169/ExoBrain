# -*- coding: utf-8 -*-
"""Import a corpus version into a knowledge base and record the index evidence.

只做四件事：登录、建（或复用）知识库、按 manifest 上传正文、等导入终态。
「上传成功」不等于「可检索」，所以必须等 ingestion 终态并记下 `indexRevisionId`
与切片数，供后续比较固定语料与索引版本。
"""
import argparse
import hashlib
import io
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

state = {"csrf": None, "cookie": None}


def call(base, method, path, payload=None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(base + path, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if state["csrf"]:
        request.add_header("X-CSRF-Token", state["csrf"])
    if state["cookie"]:
        request.add_header("Cookie", state["cookie"])
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
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


def upload(base, kb_id, path):
    boundary = "----exobrain%d" % uuid.uuid4().int
    filename = os.path.basename(path)
    with io.open(path, "rb") as handle:
        body = handle.read()
    parts = []
    parts.append(("--%s\r\n" % boundary).encode("utf-8"))
    parts.append(('Content-Disposition: form-data; name="kbId"\r\n\r\n%s\r\n' % kb_id).encode("utf-8"))
    parts.append(("--%s\r\n" % boundary).encode("utf-8"))
    parts.append(('Content-Disposition: form-data; name="file"; filename="%s"\r\n' % filename).encode("utf-8"))
    parts.append(b"Content-Type: text/markdown\r\n\r\n")
    parts.append(body)
    parts.append(("\r\n--%s--\r\n" % boundary).encode("utf-8"))
    payload = b"".join(parts)
    request = urllib.request.Request(base + "/api/documents/upload", data=payload, method="POST")
    request.add_header("Content-Type", "multipart/form-data; boundary=%s" % boundary)
    if state["csrf"]:
        request.add_header("X-CSRF-Token", state["csrf"])
    if state["cookie"]:
        request.add_header("Cookie", state["cookie"])
    with urllib.request.urlopen(request, timeout=300) as response:
        raw = response.read().decode("utf-8")
        return response.status, (json.loads(raw) if raw.strip() else None)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:18080")
    parser.add_argument("--corpus", required=True, help="语料版本目录（含 manifest.json）")
    parser.add_argument("--kb-name", required=True)
    parser.add_argument("--kb-id", default="", help="复用已有知识库；留空则新建")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    base = args.base.rstrip("/")

    status, login = call(base, "POST", "/api/auth/login", {"username": "admin", "password": "localdev123"})
    if status != 200:
        sys.exit("login failed: %s" % status)
    state["csrf"] = login["data"]["csrfToken"]

    manifest = json.load(io.open(os.path.join(args.corpus, "manifest.json"), encoding="utf-8"))
    kb_id = args.kb_id
    if not kb_id:
        status, created = call(base, "POST", "/api/knowledge-bases",
                               {"name": args.kb_name, "description": "语料版本 %s" % manifest["version"]})
        if status not in (200, 201):
            sys.exit("create kb failed: %s %s" % (status, created))
        kb_id = created["data"]["knowledgeBaseId"]
    print("kb:", kb_id)

    evidence = {"corpusVersion": manifest["version"], "corpusDir": args.corpus,
                "knowledgeBaseId": kb_id, "documents": []}
    for entry in manifest["documents"]:
        path = os.path.join(args.corpus, entry["file"])
        with io.open(path, "rb") as handle:
            digest = hashlib.sha256(handle.read()).hexdigest()
        if digest != entry["sha256"]:
            sys.exit("sha256 mismatch for %s: %s != %s" % (path, digest, entry["sha256"]))
        status, uploaded = upload(base, kb_id, path)
        if status not in (200, 201):
            sys.exit("upload failed for %s: %s %s" % (entry["file"], status, uploaded))
        document_id = uploaded["data"]["documentId"]
        job = {}
        for _ in range(150):
            time.sleep(2)
            _, body = call(base, "GET", "/api/documents/%s/ingestion" % document_id)
            job = (body or {}).get("data") or {}
            if job.get("status") in ("SUCCEEDED", "FAILED"):
                break
        evidence["documents"].append({
            "file": entry["file"], "sha256": digest, "documentId": document_id,
            "jobId": uploaded["data"].get("jobId"), "status": job.get("status"),
            "chunksDone": job.get("chunksDone"), "chunksTotal": job.get("chunksTotal"),
            "indexRevisionId": job.get("indexRevisionId"), "errorCode": job.get("errorCode"),
        })
        print(entry["file"], job.get("status"), job.get("chunksDone"), job.get("indexRevisionId"))
        if job.get("status") != "SUCCEEDED":
            sys.exit("ingestion did not succeed for %s: %s" % (entry["file"], job))

    with io.open(args.out, "w", encoding="utf-8") as handle:
        json.dump(evidence, handle, ensure_ascii=False, indent=2)
    print("written", args.out)


if __name__ == "__main__":
    main()