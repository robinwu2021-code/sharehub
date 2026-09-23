// 页内 tab 与菜单的对齐守卫。
//
// 页面自己写一份 `const TABS = [{key,label}]` 时，它与 nav.ts 是两份互不知情的真源，
// 改一处不改另一处没有任何东西会报错。
//
// 这条用例查的是**菜单 → 页面**这个方向：菜单里每条带 `?tab=` / `?view=` 的叶子，
// 页面都必须真有那个 tab。不成立就是**死菜单项**——点进去落到页面的默认 tab，
// 用户以为自己点错了。
//
// 反方向（页面有、菜单没登记）**不在这里查**：页面的默认 tab 对应菜单里不带 query
// 的那条裸路径，而"默认是哪一个"由页面自己决定、还可能随分期变
// （`/marketing` 阶段 1 是公告、阶段 2 起是优惠券）。静态解析猜不准，
// 猜错就会给出一条假警报。那个方向由 `navTabs` 在开发期抛错来管
// ——页面迁到 `useNavTabs` 时当场就炸，比一条基线数字准。
import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { NAV, leafParts, normPath } from "./nav";

const APP = join(__dirname, "..", "app");

function pagesWithLocalTabs(): { path: string; file: string }[] {
  const out: { path: string; file: string }[] = [];
  for (const name of readdirSync(APP)) {
    const dir = join(APP, name);
    if (!statSync(dir).isDirectory() || name === "dev") continue;
    const file = join(dir, "page.tsx");
    try {
      if (readFileSync(file, "utf8").includes("const TABS = [")) out.push({ path: `/${name}`, file });
    } catch { /* 该目录没有 page.tsx */ }
  }
  return out;
}

function tabKeys(src: string): string[] {
  const i = src.indexOf("const TABS = [");
  let depth = 0, end = i;
  for (let j = i + "const TABS = ".length; j < src.length; j++) {
    if (src[j] === "[") depth++;
    else if (src[j] === "]" && --depth === 0) { end = j; break; }
  }
  return [...src.slice(i, end + 1).matchAll(/key:\s*"([^"]+)"/g)].map((m) => m[1]);
}

describe("菜单指向的 tab，页面必须真有", () => {
  it("没有死菜单项", () => {
    const dead: string[] = [];
    for (const { path, file } of pagesWithLocalTabs()) {
      const keys = new Set(tabKeys(readFileSync(file, "utf8")));
      for (const sec of NAV) {
        for (const leaf of sec.children ?? []) {
          const parts = leafParts(leaf.href);
          if (normPath(parts.path) !== path) continue;
          const key = parts.tab ?? parts.view;
          if (key && !keys.has(key)) dead.push(`${leaf.href}（${leaf.label}）`);
        }
      }
    }
    expect(dead).toEqual([]);
  });
});
