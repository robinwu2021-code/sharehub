// 风格 × 主题色的对比度卡口。
//
// 风格（data-style）与主题色（data-theme）任意组合，所以两边各自保证：
//   · 风格只动语义色 → 锁「清爽」风格的语义色（亮 / 暗）；
//   · 主题色只动主色 → 对**每一套**主题色锁「主色上的字」与「主色写成的文字」。
//     新增主题色时不用改这里：清单直接取 stores/theme.ts 的 THEMES。
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
import { THEMES } from "./stores/theme";

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

const W = "#ffffff";
const base = block(":root");
const baseDark = block(".dark");

describe("基线中性色（所有风格共用）", () => {
  it("亮色", () =>
    expectPairs(base, [
      ["正文", "gray-12", W, 7],
      ["次要文字", "gray-11", W, 4.5],
      ["次要文字压表头浅带", "gray-11", "gray-2", 4.5],
      ["占位符", "gray-9", W, 3],
      ["表格行线", "gray-6", W, 1.3],
      ["元件描边", "gray-7", W, 1.45],
    ]));
});

describe("「清爽」风格的语义色", () => {
  it("亮色：浅底上的文字 + 实心底上的白字", () =>
    expectPairs(block('[data-style="clean"]'), [
      ...(["green", "amber", "red", "blue"] as const).flatMap((c): Pair[] => [
        [`${c} 文字 / 浅底`, `${c}-text`, `${c}-subtle`, 4.5],
        [`白字 / ${c} 实心`, W, `${c}-solid`, 4.5],
      ]),
    ]));
  it("暗色：浅底上的文字 + 实心底上的深字（暗色下 --on-semantic 是 gray-1）", () => {
    const t = { ...block('.dark[data-style="clean"]'), "gray-1": baseDark["gray-1"] };
    expectPairs(t, [
      ...(["green", "amber", "red", "blue"] as const).flatMap((c): Pair[] => [
        [`${c} 文字 / 浅底`, `${c}-text`, `${c}-subtle`, 4.5],
        [`深字 / ${c} 实心`, "gray-1", `${c}-solid`, 4.5],
      ]),
    ]);
  });
});

describe("每一套主题色（亮色）", () => {
  for (const th of THEMES) {
    it(`${th.label}（${th.key}）：主色上的字、主色写成的文字`, () => {
      const t = block(`[data-theme="${th.key}"]`);
      // --primary-text 没单独给就等于主色（:root 里的默认值）
      const tokens = { ...t, "primary-text": t["primary-text"] ?? t["primary"] };
      expectPairs(tokens, [
        ["主色上的字（主按钮）", "primary-foreground", "primary", 4.5],
        ["主色文字压白底（链接、选中 tab）", "primary-text", W, 4.5],
        ["主色文字压浅带", "primary-text", base["gray-2"], 4.5],
      ]);
    });
  }
});

describe("墨青（teal）暗色", () => {
  it("主色上的字、卡片上的主色", () =>
    expectPairs(block('.dark[data-theme="teal"]'), [
      ["主色上的字", "primary-foreground", "primary", 4.5],
      ["卡片上的主色（链接）", "primary", baseDark["gray-2"], 4.5],
    ]));
});
