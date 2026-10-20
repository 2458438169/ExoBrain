#!/usr/bin/env node
/**
 * UX-07 回归：改输入区与分包之后，真实后端下仍然"一次点击只创建一个运行"、
 * 深链接刷新可恢复、账号构建可登录、分包失败有可读错误态、四档宽度无横向溢出。
 *
 *   node scripts/perf/verify-first-load-regressions.mjs --public http://127.0.0.1:18081/ \
 *       --admin http://127.0.0.1:18082/
 */
import { createRequire } from "node:module";
import { resolve as resolvePath } from "node:path";

const require = createRequire(import.meta.url);
const { chromium } = require(resolvePath(process.cwd(), "ui/node_modules/playwright-core"));

const args = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  args.set(process.argv[index].replace(/^--/, ""), process.argv[index + 1]);
}
const publicUrl = args.get("public") ?? "http://127.0.0.1:18081/";
const adminUrl = args.get("admin") ?? "http://127.0.0.1:18082/";
const results = [];

function record(name, ok, detail = "") {
  results.push({ name, ok, detail });
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  " + detail : ""}`);
}

async function main() {
  const browser = await chromium.launch({ channel: "chrome", headless: true });

  // 1) 一次点击：只创建一个会话、一次提交，页面进入会话视图
  {
    const context = await browser.newContext({ viewport: { width: 1366, height: 900 } });
    const page = await context.newPage();
    const posts = [];
    page.on("request", (request) => {
      if (request.method() === "POST" && request.url().includes("/chat-messages")) {
        posts.push(request.postData() ?? "");
      }
    });
    await page.goto(publicUrl, { waitUntil: "domcontentloaded" });
    const button = page.locator('button[aria-label^="直接发送推荐问题"]').first();
    await button.waitFor({ state: "visible", timeout: 30_000 });
    await button.click();
    await page.waitForTimeout(6000);
    const keys = new Set(posts.map((body) => (body.match(/idempotencyKey":"([^"]+)"/) ?? [])[1]));
    record("一次点击只发一次提交", posts.length === 1, `POST=${posts.length} keys=${[...keys].join(",")}`);

    // 2) 深链接刷新：会话仍在，且不会再次 POST
    const sessionUrl = page.url();
    const before = posts.length;
    await page.reload({ waitUntil: "domcontentloaded" });
    await page.waitForTimeout(4000);
    const stillThere = await page.locator("textarea").isVisible().catch(() => false);
    record("会话深链接刷新后仍可用", /\/chat\/[0-9a-f-]{36}/.test(sessionUrl) && stillThere, sessionUrl);
    record("刷新不会重复提交", posts.length === before, `POST=${posts.length}`);

    // 3) 分包失败：拦掉阅读区分包，应当出现可读错误态而不是白屏
    const blocked = await context.newPage();
    await blocked.route(/AgentChatHistory-[^/]+\.js|agentChatView/, (route) => route.abort());
    await blocked.goto(sessionUrl, { waitUntil: "domcontentloaded" });
    await blocked.waitForTimeout(4000);
    const alertText = await blocked.locator('[role="alert"]').first().innerText().catch(() => "");
    record("分包失败显示可读错误与重新加载入口", alertText.includes("加载") || alertText.includes("重新加载"), alertText.slice(0, 60));

    // 4) 四档宽度无横向溢出
    for (const width of [320, 390, 768, 1440]) {
      const viewportPage = await context.newPage();
      await viewportPage.setViewportSize({ width, height: 900 });
      await viewportPage.goto(publicUrl, { waitUntil: "domcontentloaded" });
      await viewportPage.waitForTimeout(2500);
      const metrics = await viewportPage.evaluate(() => ({
        scroll: document.documentElement.scrollWidth,
        client: document.documentElement.clientWidth,
      }));
      record(`宽度 ${width} 无横向溢出`, metrics.scroll <= metrics.client + 1, `${metrics.scroll}/${metrics.client}`);
      await viewportPage.close();
    }
    await context.close();
  }

  // 5) 账号构建可加载并显示登录入口
  {
    const context = await browser.newContext({ viewport: { width: 1366, height: 900 } });
    const page = await context.newPage();
    await page.goto(adminUrl, { waitUntil: "domcontentloaded" });
    await page.waitForTimeout(4000);
    const text = await page.locator("body").innerText();
    record("账号构建可加载（出现登录相关文案）", /登录|账号|密码/.test(text), text.slice(0, 60).replace(/\s+/g, " "));
    await context.close();
  }

  await browser.close();
  const failed = results.filter((item) => !item.ok);
  console.log(`\nsummary: ${results.length - failed.length}/${results.length} passed`);
  process.exit(failed.length ? 1 : 0);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
