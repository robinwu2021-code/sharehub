/*
 * 生产产物的底线检查。**没有它，构建成功 = 什么都没证明**。
 *
 * next build 退出码 0、目录齐全、首页 curl 200 —— 这三件事同时成立，
 * 产物仍然可能是废的：资源前缀错了是白屏，开发机地址烧进去是「看起来像没数据」，
 * mock 没关掉是每个数字都假而界面毫无异样。三种都不会让构建失败。
 *
 * 根因也不是手滑：**Next 在生产构建时照样读 `.env.local`**，
 * 而本仓那份文件装的就是开发机配置（NEXT_PUBLIC_API_BASE=http://localhost:8080、
 * NEXT_PUBLIC_USE_MOCK=1）。命令行传的 process.env 优先级最高压得住它 ——
 * 但「传了没有」必须有人当场检查，否则下次照样静默漏。
 *
 * 用法：`npm run build:prod`（构建 + 本检查）。
 */
import { readFileSync, readdirSync, statSync, existsSync } from "node:fs";
import { join } from "node:path";

const OUT = new URL("../out/", import.meta.url).pathname;
if (!existsSync(join(OUT, "index.html"))) {
  console.error("✗ out/index.html 不存在 —— 先跑构建");
  process.exit(1);
}

function allFiles(dir) {
  const out = [];
  for (const e of readdirSync(dir)) {
    const f = join(dir, e);
    if (statSync(f).isDirectory()) out.push(...allFiles(f));
    else if (/\.(js|html|txt|json)$/.test(e)) out.push(f);
  }
  return out;
}
const files = allFiles(OUT);
const problems = [];

// 1. 开发机地址。烧进去的话线上会去打**访问者自己的** localhost：接口全连不上，
//    页面骨架照常渲染 —— 看起来像「没数据」，而不像「配错了」。
const devHits = [];
for (const f of files) {
  const m = readFileSync(f, "utf8").match(/https?:\/\/(?:127\.0\.0\.1|localhost)(?::\d+)?/);
  if (m) devHits.push(`${f.slice(OUT.length)} → ${m[0]}`);
}
if (devHits.length) {
  problems.push(`${devHits.length} 个文件里有开发机地址，例如 ${devHits[0]}\n`
    + "    → 显式传 NEXT_PUBLIC_API_BASE=（空串 = 同源，走 nginx 反代）");
}

// 2. mock 开关。漏配 = 静默退回 mock（判据是 `!== "0"`），运营端会连着假数据上线。
//    构建期被内联成常量，产物里读不出原值 —— 所以查的是**根布局输出的那个标记**
//    （lib/api-mode.ts 的 API_MODE 之所以存在就是为了让它可被检查）。
const html = readFileSync(join(OUT, "index.html"), "utf8");
if (/name="api-mode" content="mock"/.test(html)) {
  problems.push("产物是 mock 模式\n    → 显式传 NEXT_PUBLIC_USE_MOCK=0");
}

// 3. 资源前缀。配了 BASE_PATH 却没落到产物里 = 一个 chunk 都取不到，首页仍返回 200。
const base = process.env.NEXT_PUBLIC_BASE_PATH;
if (base && !new RegExp(`(src|href)="${base}/_next/`).test(html)) {
  const sample = html.match(/(?:src|href)="[^"]*_next[^"]*"/)?.[0] ?? "（一个 _next 引用都没有）";
  problems.push(`资源没带 ${base} 前缀：${sample}`);
}

if (problems.length) {
  console.error("✗ 生产产物检查未通过：\n" + problems.map((p) => "  · " + p).join("\n"));
  process.exit(1);
}
console.log(`✓ 生产产物检查通过（扫了 ${files.length} 个文件）`);
