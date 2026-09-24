import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { keepWithinTab } from "./hooks/use-page-tab";

/**
 * 「一个 query 喂多个 tab」的页面，**不许用 `keepPreviousData`**。
 *
 * <h3>为什么这是个卡口而不是一次修复</h3>
 * 这些页面的 `queryKey` 形如 `[域名, tab, page, size, …]`。换 tab 换的是 key，
 * 而 `keepPreviousData` 的语义是「key 变了就先顶上上一次的数据」——
 * 它**分不出「翻页」和「换 tab」**。
 *
 * 于是切 tab 那一帧，新 tab 的列拿着上一个 tab 的行去渲染：`rowKey` 全是
 * undefined（React 报重复 key），`StatusBadge` 查不到状态当场抛
 * `Cannot read properties of undefined (reading 'tone')`。
 * 2026-09-24 在财务页实测复现（从「分润规则」点到「发票」）。
 *
 * <b>写法是对的、类型是对的、测试也不会红</b> —— 只有真去点那一下才看得见。
 * 而下一个加 tab 的人多半会照着旁边的页面抄 `placeholderData: keepPreviousData`。
 * 所以留个卡口，用 {@link keepWithinTab} 替代。
 */

const PAGES_DIR = "app";

/** 取出一个 `useQuery({...})` 调用的花括号块（按深度配对，正则做不到）。 */
function queryBlocks(src: string): string[] {
  const out: string[] = [];
  const open = /useQuery[^(]*\(\{/g;
  let m: RegExpExecArray | null;
  while ((m = open.exec(src))) {
    let depth = 1;
    let j = m.index + m[0].length;
    const start = j;
    while (j < src.length && depth > 0) {
      if (src[j] === "{") depth++;
      else if (src[j] === "}") depth--;
      j++;
    }
    out.push(src.slice(start, j));
  }
  return out;
}

function pageFiles(): string[] {
  return readdirSync(PAGES_DIR, { withFileTypes: true })
    .filter((d) => d.isDirectory())
    .map((d) => join(PAGES_DIR, d.name, "page.tsx"))
    .filter((p) => {
      try { readFileSync(p); return true; } catch { return false; }
    });
}

/**
 * 还没改过来的页面。**这个清单只准变短**（同 design-tokens.test.ts 的棘轮约定）。
 *
 * 现在是空的 —— 全站九处都改完了（devices / finance 两页当时被另一个会话占着，
 * 等它们提交后补上）。留着这个空数组不是摆设：下一次再出现「明知有问题但这轮改不到」
 * 的页面时，该挂进来而不是把上面的卡口调松。
 */
const KNOWN_OFFENDERS: string[] = [];

describe("多 tab 共用一个 query 的页面", () => {
  it("不许用 keepPreviousData——换 tab 会拿上一个 tab 的行渲染新 tab 的列", () => {
    const offenders: string[] = [];
    for (const f of pageFiles()) {
      const src = readFileSync(f, "utf-8");
      for (const blk of queryBlocks(src)) {
        if (!blk.includes("keepPreviousData")) continue;
        const key = /queryKey:\s*\[([^\]]*)\]/.exec(blk);
        if (!key) continue;
        const parts = key[1].split(",").map((p) => p.trim());
        // 约定：域名在第 1 位、tab 在第 2 位
        if (parts[1] === "tab") offenders.push(f);
      }
    }
    const fresh = offenders.filter((f) => !KNOWN_OFFENDERS.includes(f));
    expect(
      fresh,
      `这些页面的 queryKey 里带 tab 又开了 keepPreviousData——切 tab 会用上一个 tab 的数据渲染新 tab 的列，
轻则整表错位，重则 StatusBadge 查不到状态直接抛。
改用 placeholderData: keepWithinTab(tab)（lib/hooks/use-page-tab.ts）：
同一个 tab 内翻页照样不闪白，换了 tab 就不顶。`,
    ).toEqual([]);

    // 清单只准变短：改好了就从 KNOWN_OFFENDERS 里删掉，
    // 删漏了这里会红——否则「已经修好」和「忘了修」在清单上长得一样。
    expect(
      KNOWN_OFFENDERS.filter((f) => !offenders.includes(f)),
      "这些已经不再违规了，请从 KNOWN_OFFENDERS 里删掉",
    ).toEqual([]);
  });
});

describe("keepWithinTab", () => {
  const prevOf = (tab: string) => ({ queryKey: ["fin", tab, 1, 20] as const });

  it("同一个 tab 内翻页：顶上上一页，表格不塌成空态", () => {
    expect(keepWithinTab<string>("invoices")("上一页", prevOf("invoices"))).toBe("上一页");
  });

  it("换了 tab：不顶——这正是崩溃的那一帧", () => {
    expect(keepWithinTab<string>("invoices")("分润规则的行", prevOf("rules"))).toBeUndefined();
  });

  it("拿不到上一次的 key 就不顶——顶错的代价大于闪一下", () => {
    expect(keepWithinTab<string>("invoices")("来路不明", undefined)).toBeUndefined();
  });
});
