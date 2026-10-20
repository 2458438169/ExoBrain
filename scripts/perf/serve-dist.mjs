#!/usr/bin/env node
/**
 * 本地静态服务：给构建产物加 gzip（与线上 nginx 一致），并把 /api、/sse 代理到真实后端，
 * 让"首屏可用"可以用真实接口测量，而不是让按钮永远处于"正在连接助手"。
 *
 *   node scripts/perf/serve-dist.mjs --dir ui/dist-public --port 18081 --api http://127.0.0.1:18080
 */
import { createServer, request as httpRequest } from "node:http";
import { gzipSync } from "node:zlib";
import { createReadStream, existsSync, readFileSync, statSync } from "node:fs";
import { extname, join, normalize, resolve } from "node:path";

const args = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  args.set(process.argv[index].replace(/^--/, ""), process.argv[index + 1]);
}

const root = resolve(args.get("dir") ?? "ui/dist-public");
const port = Number(args.get("port") ?? 18081);
const apiBase = args.get("api") ?? "";
const basePath = args.get("base") ?? "/";

const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".woff2": "font/woff2",
  ".ico": "image/x-icon",
};

const gzipCache = new Map();

// 账号构建的接口前缀是 /admin/api 与 /admin/sse，转发时要去掉这段前缀。
const apiPrefix = args.get("api-prefix") ?? "";

function gzipped(path) {
  const stat = statSync(path);
  const key = `${path}:${stat.mtimeMs}:${stat.size}`;
  const cached = gzipCache.get(key);
  if (cached) return cached;
  const body = gzipSync(readFileSync(path), { level: 6 });
  gzipCache.set(key, body);
  return body;
}

function proxy(req, res, targetPath) {
  const target = new URL(apiBase);
  const upstream = httpRequest(
    { hostname: target.hostname, port: target.port, path: targetPath, method: req.method, headers: { ...req.headers, host: target.host } },
    (upstreamRes) => {
      res.writeHead(upstreamRes.statusCode ?? 502, upstreamRes.headers);
      upstreamRes.pipe(res);
    },
  );
  upstream.on("error", () => {
    res.writeHead(502, { "content-type": "application/json" });
    res.end('{"code":502,"message":"upstream unavailable","data":null}');
  });
  req.pipe(upstream);
}

const server = createServer((req, res) => {
  const url = (req.url ?? "/").split("?")[0];
  const query = (req.url ?? "").includes("?") ? "?" + (req.url ?? "").split("?")[1] : "";
  if (apiBase && apiPrefix && url.startsWith(apiPrefix)) {
    proxy(req, res, url.slice(apiPrefix.length) + query);
    return;
  }
  if (apiBase && (url.startsWith("/api") || url.startsWith("/sse"))) {
    proxy(req, res, req.url);
    return;
  }
  const relative = url.startsWith(basePath) ? url.slice(basePath.length) : url.slice(1);
  let filePath = join(root, normalize(relative || "index.html"));
  if (!filePath.startsWith(root) || (existsSync(filePath) && statSync(filePath).isDirectory())) {
    filePath = join(filePath, "index.html");
  }
  if (!existsSync(filePath)) {
    // SPA 回退：无扩展名的路由交给 index.html，带扩展名的缺失资源保持 404。
    if (extname(relative)) {
      res.writeHead(404, { "content-type": "text/plain" });
      res.end("not found");
      return;
    }
    filePath = join(root, "index.html");
  }
  const type = TYPES[extname(filePath)] ?? "application/octet-stream";
  const acceptsGzip = String(req.headers["accept-encoding"] ?? "").includes("gzip");
  if (acceptsGzip && statSync(filePath).size > 1024) {
    const body = gzipped(filePath);
    res.writeHead(200, {
      "content-type": type,
      "content-encoding": "gzip",
      "content-length": body.length,
      "cache-control": extname(filePath) === ".html" ? "no-cache" : "public, max-age=31536000, immutable",
    });
    res.end(body);
    return;
  }
  res.writeHead(200, { "content-type": type });
  createReadStream(filePath).pipe(res);
});

server.listen(port, "127.0.0.1", () => {
  console.log(`serving ${root} on http://127.0.0.1:${port}${basePath} (gzip on, api -> ${apiBase || "none"})`);
});
