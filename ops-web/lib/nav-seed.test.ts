import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { NAV } from "./nav";
import { tNav } from "./i18n/nav-labels";

/**
 * 菜单真源从 `nav.ts` 迁到 `iam_menu`（V67 建表灌数，V71 补 section 码，V74 补叶子码）——**切换那一刻两边必须逐节点相等**。
 *
 * <h3>为什么非要有这一条</h3>
 * 迁移里那 127 行 INSERT 是 `backend/scripts/gen-menu-seed.py` 生成的。
 * 手写做不到「相等」，只能做到「看起来差不多」——
 * 而差掉的那几行**不会有任何东西报错**：菜单少一项，用的人以为没这功能。
 *
 * 迁移前那张表已经示范过一次了：生产 `iam_menu` 只有 12 行、零叶子，
 * 其中两个菜单 nav.ts 里根本不存在、另外五个顶级菜单缺失 ——
 * 它是首次启动灌的存货，此后 nav.ts 改了无数次，一直没人发现。
 *
 * <h3>红了怎么办</h3>
 * 说明 nav.ts 改了而迁移没跟上。**不要手改迁移**，重新生成：
 * ```
 * cd ops-web && npx vitest run lib/nav-seed.test.ts   # 看差在哪
 * # 改完 nav.ts 后重新导出并生成，步骤见 gen-menu-seed.py 的文档串
 * ```
 * **永远新开一个迁移，不要改已提交的那个** —— 别人的库可能已经跑过它，
 * 改了 Flyway 会因校验和不符拒绝启动。V71 就是这么来的。
 */

// 相对**仓库根**，不是相对本文件 —— vitest 的 cwd 是 ops-web/
const MIGRATION = "../backend/sharehub-app/src/main/resources/db/migration/V81__menu_admin_leaf_and_i18n.sql";

type Row = Record<string, string>;

const COLS = [
  "menu_no", "parent_no", "name", "name_en", "name_ar", "type", "path", "icon", "group_name", "sort",
  "perm", "phase", "ready", "module", "modules", "match_paths", "pin_bottom", "portal_for",
];

/**
 * 解析生成出来的 VALUES 行。**只解析自己生成的格式**，不是通用 SQL 解析器 ——
 * 每个值要么是 NULL、要么是整数、要么是单引号串（内部 '' 转义）。
 */
function parseRows(sql: string): Row[] {
  const out: Row[] = [];
  for (const line of sql.split("\n")) {
    const t = line.trim();
    if (!t.startsWith("(")) continue;
    const body = t.replace(/^\(/, "").replace(/\),?;?$/, "");
    const vals: string[] = [];
    let i = 0;
    while (i < body.length) {
      while (body[i] === " " || body[i] === ",") i++;
      if (i >= body.length) break;
      if (body[i] === "'") {
        let v = "";
        i++;
        while (i < body.length) {
          if (body[i] === "'" && body[i + 1] === "'") { v += "'"; i += 2; continue; }
          if (body[i] === "'") { i++; break; }
          v += body[i++];
        }
        vals.push(v);
      } else {
        let v = "";
        while (i < body.length && body[i] !== ",") v += body[i++];
        vals.push(v.trim());
      }
    }
    expect(vals.length, `这一行的列数对不上：${t.slice(0, 60)}…`).toBe(COLS.length);
    out.push(Object.fromEntries(COLS.map((c, k) => [c, vals[k]])));
  }
  return out;
}

/** nav.ts → 与迁移同形的行，用于逐字段比对。 */
function rowsFromNav(): Row[] {
  const j = (v?: string[]) => (v && v.length ? JSON.stringify(v) : "NULL");
  const out: Row[] = [];
  NAV.forEach((s, si) => {
    out.push({
      menu_no: `M_${s.key}`, parent_no: "NULL", name: s.label,
      name_en: tNav(s.label, "en"), name_ar: tNav(s.label, "ar"),
      // DIR=目录、MENU=可点页面，对齐冻结契约；没有叶子的 section 自己就是一页
      // 两档：MENU（分组）/ ITEM（叶子），词表由 V68 钉在列注释上
      type: "MENU",
      path: s.href, icon: s.icon ?? "NULL", group_name: "NULL", sort: String(si + 1),
      perm: s.perm ?? "NULL", phase: String(s.phase ?? 1), ready: "0",
      module: s.module ?? "NULL", modules: j(s.modules), match_paths: j(s.match),
      pin_bottom: s.pinBottom ? "1" : "0", portal_for: j(s.portalFor as string[] | undefined),
    });
    (s.children ?? []).forEach((c, li) => {
      out.push({
        menu_no: `M_${s.key}__${li + 1}`, parent_no: `M_${s.key}`, name: c.label,
        name_en: tNav(c.label, "en"), name_ar: tNav(c.label, "ar"), type: "ITEM",
        path: c.href, icon: "NULL", group_name: c.group ?? "NULL", sort: String(li + 1),
        perm: c.perm ?? "NULL", phase: String(c.phase ?? 1), ready: c.ready ? "1" : "0",
        module: "NULL", modules: "NULL", match_paths: "NULL",
        pin_bottom: "0", portal_for: "NULL",
      });
    });
  });
  return out;
}

describe("iam_menu 的种子必须与 nav.ts 逐节点相等", () => {
  const sql = readFileSync(MIGRATION, "utf-8");
  const fromSql = parseRows(sql);
  const fromNav = rowsFromNav();

  it("节点数一致——少一行就是菜单里少一项，而那不会报错", () => {
    expect(fromSql.length).toBe(fromNav.length);
  });

  it("逐行逐字段一致", () => {
    // 先按 menu_no 对齐再比，否则顺序差一位会报出 127 条假差异，真正差的那条反而埋了
    const bySql = new Map(fromSql.map((r) => [r.menu_no, r]));
    const diffs: string[] = [];
    for (const want of fromNav) {
      const got = bySql.get(want.menu_no);
      if (!got) { diffs.push(`${want.menu_no}（"${want.name}"）在迁移里没有`); continue; }
      for (const c of COLS) {
        if (got[c] !== want[c]) diffs.push(`${want.menu_no}.${c}: 迁移=${got[c]} / nav.ts=${want[c]}`);
      }
    }
    for (const got of fromSql) {
      if (!fromNav.some((w) => w.menu_no === got.menu_no)) {
        diffs.push(`${got.menu_no}（"${got.name}"）只在迁移里有，nav.ts 没有`);
      }
    }
    expect(diffs, "nav.ts 改了而迁移没跟上——重新生成，别手改迁移").toEqual([]);
  });
});
