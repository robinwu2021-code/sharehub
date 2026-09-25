import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { missingTabs } from "./nav";
import { BACKEND_ROLE_PERMS } from "./permissions";

/**
 * **每个页面的每个 tab 都能在 `nav.ts` 里解析出来**（否则整页白屏）。
 *
 * <h3>为什么要有这一条</h3>
 * `navTabs` 在开发期对「未登记的 tab」**直接抛错**（生产回落成 key）。抛错发生在渲染期，
 * 表现是**整页白屏 + 控制台一条红**，而不是少一个页签。
 *
 * 2026-09-25 实测：`/agents` 就是这样白屏的 —— `TAB_KEYS[0]` 是 `applies`，
 * 于是不带 `?tab=` 的那条裸叶（「代理商档案」）被当成了 `applies`，
 * 真正的 `profiles` 在菜单里就找不到了。**这个 bug 在仓库里躺了很久**：
 * 页面代码、菜单、类型、既有测试全都是绿的，只有真的打开那一页才看得见。
 *
 * `missingTabs` 这个纯函数本来就是为这道卡口写的（它的文档串写着「必须整组一起问」），
 * 但**从来没有任何代码调用过它** —— 工具做好了、没接上，比没做更难发现。
 *
 * <h3>它怎么做到不随页面漂移</h3>
 * 不维护一份「页面 → tab」的清单（那份清单迟早和页面对不上，而且对不上时它是绿的）。
 * 直接扫 `app/**\/page.tsx` 里的 `useNavTabs(...)` 调用，从同一个文件里取出那个 key 数组常量，
 * 按**超管**（拥有全部权限，不会因判权少掉 tab）去问。
 *
 * <h3>红了怎么办</h3>
 * 两种修法，按这个 tab 的性质二选一：
 * 1. 它是菜单叶 → 往 `nav.ts` 补登记（同时要改菜单迁移，见 `nav-seed.test.ts`）；
 * 2. 它只是页内子视图 → 在 key 数组里写成 `{ key, label }`；
 * 3. 它是**页面默认 tab**（菜单里那条不带 `?tab=` 的裸叶）→ 给 `useNavTabs` 传第三个参数。
 */
const APP = "app";

/** `useNavTabs("<path>", <常量名>[, <默认 tab>])`。第三参可能是字面量或常量名。 */
const CALL = /useNavTabs\(\s*"([^"]+)"\s*,\s*([A-Za-z_$][\w$]*)\s*(?:,\s*("([^"]*)"|[A-Za-z_$][\w$]*))?\s*\)/g;
/** 同一文件里的 `usePageTab(tabs, ..., { ..., defaultKey: X })`。取它的 defaultKey。 */
const PAGE_TAB_DEFAULT = /usePageTab\([\s\S]{0,400}?defaultKey:\s*(?:"([^"]*)"|([A-Za-z_$][\w$]*))/;

function pageFiles(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out.push(...pageFiles(p));
    else if (name === "page.tsx") out.push(p);
  }
  return out;
}

/**
 * 从源码里取模块级 `const X = [...] as const;` 的**裸字符串**元素。
 *
 * `{ key, label }` 形式的元素刻意跳过：它们是页内子视图、自带名字，
 * 按定义就不该在 `nav.ts` 里登记（`navTabs` 对它们也不报未登记）。
 * 不跳过的话，里面的 `label` 会被当成又一个 tab key —— 一条假红。
 */
function arrayConst(src: string, name: string): string[] | undefined {
  const m = new RegExp(`const\\s+${name}\\s*=\\s*\\[([\\s\\S]*?)\\]\\s*as const`).exec(src);
  if (!m) return undefined;
  const bare = m[1].replace(/\{[^}]*\}/g, "");
  return [...bare.matchAll(/"([^"]+)"/g)].map((x) => x[1]);
}

/** 取 `const DEFAULT_TAB = "xxx"` 这类常量的值。 */
function stringConst(src: string, name: string): string | undefined {
  return new RegExp(`const\\s+${name}\\s*=\\s*"([^"]+)"`).exec(src)?.[1];
}

describe("页面 tab 必须都能在 nav.ts 里解析", () => {
  const files = pageFiles(APP);
  const calls: { file: string; path: string; keys: string[]; dft?: string }[] = [];

  for (const file of files) {
    const src = readFileSync(file, "utf-8");
    for (const m of src.matchAll(CALL)) {
      const [, path, keysName, dftRaw, dftLiteral] = m;
      const keys = arrayConst(src, keysName);
      // key 数组取不到就让它红：静默跳过等于这一页没被检查，而那正是本卡口要防的
      expect(keys, `${file}: 取不到 ${keysName} 的内容——它是不是不再是模块级 as const 数组了？`).toBeDefined();
      const dft = dftLiteral ?? (dftRaw ? stringConst(src, dftRaw) : undefined);
      calls.push({ file, path, keys: keys!, dft });
    }
  }

  it("扫到了所有带页签的页面（一个都扫不到 = 正则失效，本卡口静默失效）", () => {
    expect(calls.length).toBeGreaterThanOrEqual(10);
  });

  it("★ 默认 tab 两处要一致：navTabs 管名字、usePageTab 管落点", () => {
    /*
     * 只设一处不会报错，表现是**面包屑与内容对不上**：菜单里那条裸叶写着「代理商档案」，
     * 点进去却是「入驻审核」（落在 `TAB_KEYS[0]`）。2026-09-23 的 `/marketing`、
     * 2026-09-25 的 `/agents` 都栽在这里，两次都是肉眼在浏览器里撞见的。
     */
    const problems: string[] = [];
    for (const c of calls) {
      const src = readFileSync(c.file, "utf-8");
      const m = PAGE_TAB_DEFAULT.exec(src);
      const pageDft = m ? (m[1] ?? stringConst(src, m[2])) : undefined;
      if ((c.dft ?? "") !== (pageDft ?? "")) {
        problems.push(`${c.file}: useNavTabs 的默认 tab 是 ${c.dft ?? "(未设)"}，`
          + `usePageTab 的是 ${pageDft ?? "(未设)"} —— 两者必须相同`);
      }
    }
    expect(problems, `\n${problems.join("\n")}\n`).toEqual([]);
  });

  it("★ 每个 tab 都能解析（解析不到的那一页在开发期是整页白屏）", () => {
    // 超管：拥有全部权限，不会因为判权少掉 tab —— 这里要测的是「登记了没有」，不是「看得见没有」
    const admin = { role: "ADMIN" as const, perms: BACKEND_ROLE_PERMS.ADMIN };
    const problems: string[] = [];
    for (const c of calls) {
      for (const key of missingTabs(c.path, c.keys, admin, c.dft)) {
        problems.push(`${c.file}: ${c.path} 的 tab「${key}」在 nav.ts 里找不到`);
      }
    }
    expect(problems, `\n${problems.join("\n")}\n`).toEqual([]);
  });
});
