#!/usr/bin/env node
/**
 * 首屏测量：固定 5 Mbps / 150 ms RTT / 4× CPU、冷热两套缓存，逐次记录
 * 导航开始 → HTML TTFB → FCP → LCP → 骨架出现 → **推荐问题按钮可用**（真正能提交），
 * 以及首屏 JS 的压缩传输总量。
 *
 *   node scripts/perf/measure-first-load.mjs --url http://127.0.0.1:18081/ \
 *       --label baseline --runs 10 --out perf/baseline-public.json
 *
 * 只用本机已安装的 Chrome（playwright-core 的 channel=chrome），不下载浏览器。
 */
import { writeFileSync, mkdirSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, resolve as resolvePath } from "node:path";

// playwright-core 装在 ui/ 里（前端工程自带），这里按路径解析，避免为脚本再加一份根依赖。
const PLAYWRIGHT_ENTRY = resolvePath(process.cwd(), "ui/node_modules/playwright-core");
let chromium;
try {
  ({ chromium } = createRequire(import.meta.url)(PLAYWRIGHT_ENTRY));
} catch (error) {
  console.error(`playwright-core not usable at ${PLAYWRIGHT_ENTRY}: ${error}`);
  console.error("run: cd ui && npm i -D playwright-core");
  process.exit(2);
}
const args = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  args.set(process.argv[index].replace(/^--/, ""), process.argv[index + 1]);
}

const url = args.get("url") ?? "http://127.0.0.1:18081/";
const runs = Number(args.get("runs") ?? 10);
const label = args.get("label") ?? "baseline";
const out = args.get("out") ?? "";
const readySelector = args.get("ready") ?? 'button[aria-label^="直接发送推荐问题"]';
const DOWNLOAD_BYTES_PER_SECOND = (5 * 1024 * 1024) / 8;
const UPLOAD_BYTES_PER_SECOND = (1 * 1024 * 1024) / 8;
const LATENCY_MS = 150;
const CPU_SLOWDOWN = 4;

const INIT_SCRIPT = `
  window.__perf = { lcp: 0, fcp: 0, shell: 0 };
  try {
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) window.__perf.lcp = entry.startTime;
    }).observe({ type: "largest-contentful-paint", buffered: true });
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) {
        if (entry.name === "first-contentful-paint") window.__perf.fcp = entry.startTime;
      }
    }).observe({ type: "paint", buffered: true });
  } catch (error) { window.__perf.error = String(error); }
  const markShell = () => {
    const root = document.getElementById("root");
    if (root && root.children.length > 0 && !window.__perf.shell) window.__perf.shell = performance.now();
    if (!window.__perf.shell) requestAnimationFrame(markShell);
  };
  requestAnimationFrame(markShell);
`;

function percentile(values, ratio) {
  if (!values.length) return null;
  const sorted = [...values].sort((left, right) => left - right);
  const index = Math.min(sorted.length - 1, Math.ceil(sorted.length * ratio) - 1);
  return Math.round(sorted[index] * 10) / 10;
}

async function measureOnce(browser, warm) {
  const context = await browser.newContext({ viewport: { width: 1366, height: 900 } });
  const page = await context.newPage();
  const cdp = await context.newCDPSession(page);
  await cdp.send("Network.enable");
  await cdp.send("Network.setCacheDisabled", { cacheDisabled: !warm });
  await cdp.send("Network.emulateNetworkConditions", {
    offline: false,
    latency: LATENCY_MS,
    downloadThroughput: DOWNLOAD_BYTES_PER_SECOND,
    uploadThroughput: UPLOAD_BYTES_PER_SECOND,
  });
  await cdp.send("Emulation.setCPUThrottlingRate", { rate: CPU_SLOWDOWN });

  if (warm) {
    // 热缓存：同一个浏览器上下文先走一次真实访问，让 HTML/JS/CSS 进入 HTTP 缓存，再测第二次导航。
    await page.goto(url, { waitUntil: "load", timeout: 45_000 }).catch(() => {});
    await page.waitForTimeout(800);
    await page.reload({ waitUntil: "domcontentloaded", timeout: 45_000 }).catch(() => {});
    await page.waitForTimeout(300);
  }

  const resources = new Map();
  cdp.on("Network.responseReceived", (event) => {
    resources.set(event.requestId, { type: event.type, url: event.response.url });
  });
  cdp.on("Network.loadingFinished", (event) => {
    const known = resources.get(event.requestId);
    if (known) known.bytes = event.encodedDataLength;
  });

  await page.addInitScript(INIT_SCRIPT);

  const started = Date.now();
  let navigationError = null;
  try {
    await page.goto(url, { waitUntil: "domcontentloaded", timeout: 45_000 });
  } catch (error) {
    navigationError = String(error).slice(0, 200);
  }

  // 骨架 + 按钮可用：都在页面里轮询，避免把"出现占位"当成"可以提问"。
  let usable = null;
  const usableStart = Date.now();
  while (Date.now() - usableStart < 45_000) {
    try {
      usable = await page.evaluate((selector) => {
        const button = document.querySelector(selector);
        if (!button) return null;
        if (button.disabled) return null;
        const rect = button.getBoundingClientRect();
        if (rect.width < 40 || rect.height < 20) return null;
        return performance.now();
      }, readySelector);
    } catch {
      usable = null;
    }
    if (usable) break;
    await page.waitForTimeout(50);
  }
  const usableWallMs = usable ? Date.now() - started : null;

  const timing = await page.evaluate(() => {
    const navigation = performance.getEntriesByType("navigation")[0];
    return {
      ttfb: navigation ? navigation.responseStart : null,
      domContentLoaded: navigation ? navigation.domContentLoadedEventEnd : null,
      load: navigation ? navigation.loadEventEnd : null,
      fcp: window.__perf?.fcp || null,
      lcp: window.__perf?.lcp || null,
      shell: window.__perf?.shell || null,
    };
  }).catch(() => ({}));

  const scripts = [...resources.values()].filter((item) => item.type === "Script");
  const jsBytes = scripts.reduce((sum, item) => sum + (item.bytes ?? 0), 0);
  const jsCount = scripts.length;

  await context.close();
  return {
    warm,
    navigationError,
    ttfbMs: Math.round(timing.ttfb ?? 0),
    fcpMs: Math.round(timing.fcp ?? 0),
    lcpMs: Math.round(timing.lcp ?? 0),
    shellMs: Math.round(timing.shell ?? 0),
    usableMs: usable ? Math.round(usable) : null,
    usableWallMs,
    domContentLoadedMs: Math.round(timing.domContentLoaded ?? 0),
    loadMs: Math.round(timing.load ?? 0),
    jsRequests: jsCount,
    jsTransferBytes: jsBytes,
    scriptUrls: scripts.map((item) => item.url.split("/").pop()).slice(0, 12),
  };
}

async function main() {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  const records = { cold: [], hot: [] };
  try {
    for (let index = 0; index < runs; index += 1) {
      records.cold.push(await measureOnce(browser, false));
      process.stdout.write(`cold ${index + 1}/${runs} usable=${records.cold[index].usableMs}ms js=${records.cold[index].jsTransferBytes}B\n`);
    }
    for (let index = 0; index < runs; index += 1) {
      records.hot.push(await measureOnce(browser, true));
      process.stdout.write(`hot  ${index + 1}/${runs} usable=${records.hot[index].usableMs}ms js=${records.hot[index].jsTransferBytes}B\n`);
    }
  } finally {
    await browser.close();
  }

  const summarize = (list) => {
    const usable = list.map((item) => item.usableMs).filter((value) => typeof value === "number");
    return {
      runs: list.length,
      failures: list.filter((item) => item.navigationError || item.usableMs === null).length,
      usableP50Ms: percentile(usable, 0.5),
      usableP95Ms: percentile(usable, 0.95),
      usableMinMs: usable.length ? Math.min(...usable) : null,
      usableMaxMs: usable.length ? Math.max(...usable) : null,
      ttfbP50Ms: percentile(list.map((item) => item.ttfbMs), 0.5),
      fcpP50Ms: percentile(list.map((item) => item.fcpMs), 0.5),
      lcpP50Ms: percentile(list.map((item) => item.lcpMs), 0.5),
      shellP50Ms: percentile(list.map((item) => item.shellMs), 0.5),
      jsTransferP50Bytes: percentile(list.map((item) => item.jsTransferBytes), 0.5),
      jsRequestsP50: percentile(list.map((item) => item.jsRequests), 0.5),
    };
  };

  const report = {
    label,
    url,
    measuredAt: new Date().toISOString(),
    environment: {
      browser: "chrome (playwright-core channel)",
      viewport: "1366x900",
      network: "5 Mbps down / 1 Mbps up / 150 ms RTT",
      cpu: `${CPU_SLOWDOWN}x slowdown`,
      serverCompression: "gzip",
    },
    cold: summarize(records.cold),
    hot: summarize(records.hot),
    records,
  };

  if (out) {
    mkdirSync(dirname(out), { recursive: true });
    writeFileSync(out, JSON.stringify(report, null, 2));
    console.log(`written ${out}`);
  }
  console.log(JSON.stringify({ cold: report.cold, hot: report.hot }, null, 2));
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
