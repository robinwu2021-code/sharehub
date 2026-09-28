#!/usr/bin/env node
// 流程与工单端到端场景的执行器：无头 Chrome + CDP，**不起任何服务器**。
//
//   node scripts/flow-e2e/run.mjs --out <静态产物目录> [--opskit http://localhost:8090] [--only S2,S3] [--report dir]
//
// - 页面开在虚拟源 http://flow.e2e/ 下，所有请求由 CDP 拦截：静态文件从 --out 直接应答；
// - 给了 --opskit 时，/api/opskit/** 由本进程转发到真实服务（免 CORS，与生产同源反代同形）；
// - 每个场景前重写 localStorage（登录态 / 权限 / 语言），再整页打开 —— mock 模式下数据也随之重置；
// - 失败时截图，汇总写 report.json；有失败退出码为 1。
// 依赖：Node ≥ 22（内置 WebSocket / fetch）、本机 Chrome（CHROME 环境变量可覆盖路径）。
import { spawn } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname, extname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { SCENARIOS } from "./scenarios.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : d; };
const OUT = arg("out");
const OPSKIT = arg("opskit");
const ONLY = arg("only")?.split(",");
const REPORT = arg("report", join(here, "..", "..", "..", ".e2e", "report"));
if (!OUT || !existsSync(OUT)) { console.error("用法：--out <next build 的 out 目录>"); process.exit(2); }

const FIXTURES = JSON.parse(readFileSync(join(here, OPSKIT ? "fixtures.opskit.json" : "fixtures.mock.json"), "utf8"));
const HELPERS = readFileSync(join(here, "helpers.browser.js"), "utf8");
const ORIGIN = "http://flow.e2e";
const TYPES = { ".html": "text/html; charset=utf-8", ".js": "application/javascript", ".css": "text/css", ".json": "application/json", ".svg": "image/svg+xml", ".png": "image/png", ".woff2": "font/woff2", ".ico": "image/x-icon", ".txt": "text/plain" };
const CHROME = process.env.CHROME || (process.platform === "darwin" ? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" : "google-chrome");

function staticFile(url) {
  const p = decodeURIComponent(new URL(url).pathname);
  let f = join(OUT, p);
  if (existsSync(f) && statSync(f).isDirectory()) f = join(f, "index.html");
  if (!existsSync(f) && existsSync(f + ".html")) f = f + ".html";
  if (!existsSync(f)) f = join(OUT, "404.html");
  return f;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const PORT = 9400 + Math.floor(Math.random() * 400);
const prof = join(REPORT, ".chrome");
rmSync(REPORT, { recursive: true, force: true }); mkdirSync(prof, { recursive: true });
const chrome = spawn(CHROME, ["--headless=new", `--remote-debugging-port=${PORT}`, `--user-data-dir=${prof}`, "--hide-scrollbars", "--disable-gpu", "--no-first-run", "about:blank"], { stdio: "ignore" });

let ws, seq = 0;
const pending = new Map();
const send = (method, params = {}) => new Promise((res, rej) => { const i = ++seq; pending.set(i, { res, rej }); ws.send(JSON.stringify({ id: i, method, params })); });
const evaluate = async (expression) => {
  const r = await send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
  return r.result?.value;
};
const consoleErrors = [];

async function onPaused({ requestId, request }) {
  const u = new URL(request.url);
  try {
    if (OPSKIT && u.pathname.startsWith("/api/opskit/")) {
      const headers = { ...request.headers }; delete headers.Host; delete headers.Origin; delete headers.Referer;
      const r = await fetch(OPSKIT + u.pathname + u.search, { method: request.method, headers, body: request.postData });
      const body = Buffer.from(await r.arrayBuffer()).toString("base64");
      const responseHeaders = [...r.headers].filter(([k]) => !/^(content-encoding|transfer-encoding|content-length)$/i.test(k)).map(([name, value]) => ({ name, value }));
      return send("Fetch.fulfillRequest", { requestId, responseCode: r.status, responseHeaders, body });
    }
    const f = staticFile(request.url);
    return send("Fetch.fulfillRequest", { requestId, responseCode: 200, responseHeaders: [{ name: "Content-Type", value: TYPES[extname(f)] || "application/octet-stream" }], body: readFileSync(f).toString("base64") });
  } catch (e) {
    return send("Fetch.fulfillRequest", { requestId, responseCode: 502, responseHeaders: [{ name: "Content-Type", value: "application/json" }], body: Buffer.from(JSON.stringify({ message: String(e) })).toString("base64") });
  }
}

function authState(perms) {
  return JSON.stringify({ state: { realm: "STAFF", subjectNo: "admin", username: "admin", role: perms.includes("*") ? "ADMIN" : "VIEWER", perms, memberships: [], currentOperatorNo: "", token: FIXTURES.token || "e2e", operatorGen: 1 }, version: 0 });
}

const results = [];
try {
  let targets;
  for (let k = 0; k < 60; k++) { try { targets = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json(); if (targets.some((p) => p.type === "page")) break; } catch { /* Chrome 还没起来 */ } await sleep(200); }
  ws = new WebSocket(targets.find((p) => p.type === "page").webSocketDebuggerUrl);
  await new Promise((r) => ws.addEventListener("open", r));
  ws.addEventListener("message", (e) => {
    const m = JSON.parse(e.data);
    if (m.method === "Fetch.requestPaused") { onPaused(m.params); return; }
    if (m.method === "Runtime.exceptionThrown") consoleErrors.push(m.params.exceptionDetails?.exception?.description?.split("\n")[0]);
    if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(m.error.message)) : p.res(m.result); }
  });
  await send("Fetch.enable", { patterns: [{ urlPattern: ORIGIN + "/*" }] });
  await send("Page.enable"); await send("Runtime.enable");
  await send("Emulation.setDeviceMetricsOverride", { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
  await send("Page.navigate", { url: ORIGIN + "/login/" }); await sleep(1500);

  for (const s of SCENARIOS.filter((x) => !ONLY || ONLY.some((o) => x.id.startsWith(o)))) {
    const t0 = Date.now();
    consoleErrors.length = 0;
    const ls = {
      "pb-ops-auth-v2": authState(s.perms),
      "ops-locale": JSON.stringify({ state: { locale: s.locale || "zh" }, version: 0 }),
      "ops-style": JSON.stringify({ state: { styleKey: "clean" }, version: 0 }),
      ...(FIXTURES.token ? { "ops-opskit-token": FIXTURES.token } : {}),
    };
    await evaluate(`localStorage.clear(); ${Object.entries(ls).map(([k, v]) => `localStorage.setItem(${JSON.stringify(k)}, ${JSON.stringify(v)})`).join(";")}`);
    await send("Page.navigate", { url: ORIGIN + s.url(FIXTURES) });
    let r;
    try {
      await sleep(600);
      await evaluate(HELPERS);
      r = await evaluate(`(async () => { const h = window.__e2e; const fx = ${JSON.stringify(FIXTURES)};
        try { await (${s.run.toString()})(h, fx); return { ok: true, log: h.log }; }
        catch (e) { return { ok: false, log: h.log, error: String(e && e.message || e) }; } })()`);
    } catch (e) { r = { ok: false, log: [], error: String(e.message || e) }; }
    if (r.ok && consoleErrors.length) r = { ...r, ok: false, error: "页面抛出未捕获异常：" + consoleErrors.join(" / ") };
    if (!r.ok) {
      const shot = await send("Page.captureScreenshot", { format: "png" });
      writeFileSync(join(REPORT, `${s.id}.png`), Buffer.from(shot.data, "base64"));
    }
    results.push({ id: s.id, title: s.title, ok: r.ok, ms: Date.now() - t0, error: r.error, log: r.log });
    console.log(`${r.ok ? "✅" : "❌"} ${s.id} ${s.title}${r.ok ? "" : `\n     ${r.error}`}`);
  }
} finally {
  try { ws?.close(); } catch { /* 已关 */ }
  chrome.kill("SIGKILL");
}
const failed = results.filter((r) => !r.ok);
writeFileSync(join(REPORT, "report.json"), JSON.stringify({ mode: OPSKIT ? `opskit ${OPSKIT}` : "mock", at: new Date().toISOString(), passed: results.length - failed.length, failed: failed.length, results }, null, 2));
rmSync(prof, { recursive: true, force: true });
console.log(`\n${results.length - failed.length}/${results.length} 通过 · 报告 ${join(REPORT, "report.json")}`);
process.exit(failed.length ? 1 : 0);
