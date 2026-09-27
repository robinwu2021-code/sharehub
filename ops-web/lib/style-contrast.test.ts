// ink 风格与 teal 皮肤的对比度卡口。
//
// 色值一改就可能跌破 AA，而肉眼在白底上几乎分不出 4.4 和 4.6 ——
// 本项目真实踩过：旧 Badge 直接拿语义色当文字色，只有约 2.8:1（见 globals.css 注释）。
// 所以取值的每一对都在这里算一遍，而不是靠「看着还行」。
//
// 门槛：正文 7（15:1 上下更耐看，但不许跌到 7 以下）· 次要文字与语义文字 4.5 ·
// 占位符 3 · 行线 1.3 · 元件描边 1.45（经典风格分别是 1.32 / 1.47，ink 不许比它弱）。
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const css = readFileSync(join(new URL("..", import.meta.url).pathname, "app/globals.css"), "utf8");

/** 取某个选择器块里的 `--x: #hex` 声明。选择器须位于行首、且只出现一次（容忍对齐用的空格）。 */
function block(selector: string): Record<string, string> {
  const esc = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const hits = [...css.matchAll(new RegExp(`^${esc}\\s*\\{`, "gm"))];
  expect(hits.length, `选择器 ${selector} 应恰好出现一次`).toBe(1);
  const at = hits[0].index!;
  const body = css.slice(at, css.indexOf("}", at));
  const out: Record<string, string> = {};
  for (const m of body.matchAll(/--([\w-]+):\s*(#[0-9a-fA-F]{6})\b/g)) out[m[1]] = m[2].toLowerCase();
  return out;
}

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
  const f = (c: number) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
}
function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

type Pair = [label: string, fg: string, bg: string, min: number];

function expectPairs(tokens: Record<string, string>, pairs: Pair[]) {
  const v = (k: string) => (k.startsWith("#") ? k : tokens[k]);
  for (const [label, fg, bg, min] of pairs) {
    expect(v(fg), `${label}：缺 --${fg}`).toBeTruthy();
    expect(v(bg), `${label}：缺 --${bg}`).toBeTruthy();
    const c = contrast(v(fg), v(bg));
    expect(c, `${label}：${v(fg)} / ${v(bg)} = ${c.toFixed(2)}，低于 ${min}`).toBeGreaterThanOrEqual(min);
  }
}

describe("ink 风格 · 亮色", () => {
  const t = block('[data-style="ink"]');
  const W = "#ffffff";
  it("中性色阶", () =>
    expectPairs(t, [
      ["正文", "gray-12", W, 7],
      ["次要文字", "gray-11", W, 4.5],
      ["次要文字压表头浅带", "gray-11", "gray-2", 4.5],
      ["占位符", "gray-9", W, 3],
      ["表格行线", "gray-6", W, 1.3],
      ["元件描边", "gray-7", W, 1.45],
      ["灰块与白面", "gray-3", W, 1.08],
    ]));
  it("语义色：浅底上的文字 + 实心底上的白字", () =>
    expectPairs(t, [
      ...(["green", "amber", "red", "blue"] as const).flatMap((c): Pair[] => [
        [`${c} 文字 / 浅底`, `${c}-text`, `${c}-subtle`, 4.5],
        [`白字 / ${c} 实心`, W, `${c}-solid`, 4.5],
      ]),
    ]));
});

describe("ink 风格 · 暗色", () => {
  const t = block('.dark[data-style="ink"]');
  it("中性色阶（暗色下卡片是 gray-2）", () =>
    expectPairs(t, [
      ["正文", "gray-12", "gray-2", 7],
      ["次要文字", "gray-11", "gray-2", 4.5],
      ["次要文字压 muted", "gray-11", "gray-4", 4.5],
      ["占位符", "gray-9", "gray-2", 3],
      ["表格行线", "gray-6", "gray-2", 1.3],
      ["元件描边", "gray-7", "gray-2", 1.45],
      ["卡片与画布", "gray-2", "gray-1", 1.05],
    ]));
  it("语义色：浅底上的文字 + 实心底上的深字（--on-semantic 在暗色下是 gray-1）", () =>
    expectPairs(t, [
      ...(["green", "amber", "red", "blue"] as const).flatMap((c): Pair[] => [
        [`${c} 文字 / 浅底`, `${c}-text`, `${c}-subtle`, 4.5],
        [`深字 / ${c} 实心`, "gray-1", `${c}-solid`, 4.5],
      ]),
    ]));
});

describe("teal 皮肤", () => {
  it("亮色：主色上的字、白底上的主色", () => {
    const t = block('[data-theme="teal"]');
    expectPairs(t, [
      ["主色上的字", "primary-foreground", "primary", 4.5],
      ["白底上的主色（链接）", "primary", "#ffffff", 4.5],
    ]);
  });
  it("暗色：主色上的字、卡片上的主色", () => {
    const t = block('.dark[data-theme="teal"]');
    const card = block('.dark[data-style="ink"]')["gray-2"];
    expectPairs(t, [
      ["主色上的字", "primary-foreground", "primary", 4.5],
      ["卡片上的主色（链接）", "primary", card, 4.5],
    ]);
  });
});
