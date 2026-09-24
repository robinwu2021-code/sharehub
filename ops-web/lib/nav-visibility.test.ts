import { describe, it, expect } from "vitest";
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { NAV, visibleSections, visibleLeaves } from "./nav";
import { BACKEND_ROLE_PERMS } from "./permissions";
import type { Role } from "./auth";

/**
 * **每个角色看得到哪些菜单** —— 逐项快照。
 *
 * <h3>为什么要有这一条</h3>
 * 菜单真源正在从 `nav.ts` 迁到服务端判定（见
 * `docs/technical/权限与菜单-用户角色资源统一方案.md`）。这条链上每一步
 * ——给叶子补权限码、`portalFor` 挪到服务端、服务端按 perm 过滤——
 * **都会改变某些角色看得到什么**，而改错了**没有任何症状**：
 * 多出来的菜单点进去 403，少掉的菜单让人以为功能没做。
 *
 * 所以把「今天每个角色看到的菜单」逐项钉在快照里。之后每一步改动，
 * 快照文件的 diff 就是评审物：**该变的变、不该变的一行都不动**。
 *
 * <h3>红了怎么办</h3>
 * 先判断这次可见性变化是不是**有意的**：
 * - 是（比如本次给 13 个叶子补上权限码，CS/OPS 有 9 项「看得见点不开」的入口被收掉）
 *   → `UPDATE_NAV_SNAPSHOT=1 npx vitest run lib/nav-visibility.test.ts` 重写快照，
 *   把 diff 一起提交，让它在 review 里显形；
 * - 不是 → 说明改动误伤了菜单，先查为什么。
 *
 * **不要不看 diff 就重写快照** —— 那样这条卡口就只是个会自动变绿的摆设。
 *
 * <h3>数据源</h3>
 * 角色权限取 {@link BACKEND_ROLE_PERMS}（后端角色表的前端镜像）。
 * 它由 `permissions.test.ts` 与后端 `RolePerms.java` 对账，而生产的
 * `iam_role_perm` 正是 `IamSeeder` 从 `RolePerms.MAP` 灌的 —— 三者同源。
 */

const SNAPSHOT = "lib/nav-visibility.snapshot.txt";

/** 一个角色看到的全部叶子，`section›叶子` 逐行，顺序按菜单本身。 */
function visibleFor(role: Role): string[] {
  const viewer = { role, perms: BACKEND_ROLE_PERMS[role] };
  const out: string[] = [];
  for (const s of visibleSections(viewer)) {
    for (const l of visibleLeaves(s, viewer)) out.push(`${s.key}›${l.label}`);
  }
  return out;
}

function render(): string {
  const roles = Object.keys(BACKEND_ROLE_PERMS).sort() as Role[];
  const lines: string[] = [
    "# 每个角色看得到的菜单（lib/nav-visibility.test.ts 生成，勿手改）",
    "# 改动菜单可见性时这份文件会变 —— diff 就是评审物，确认是有意的再提交。",
    "",
  ];
  for (const r of roles) {
    const items = visibleFor(r);
    lines.push(`## ${r}（${items.length} 项）`);
    lines.push(...items);
    lines.push("");
  }
  return lines.join("\n");
}

describe("菜单可见性快照", () => {
  it("每个角色看到的菜单与快照逐项一致", () => {
    const now = render();
    // 刻意不自动写：自动写等于这条卡口永远绿
    if (process.env.UPDATE_NAV_SNAPSHOT === "1" || !existsSync(SNAPSHOT)) {
      writeFileSync(SNAPSHOT, now, "utf-8");
      return;
    }
    const saved = readFileSync(SNAPSHOT, "utf-8");
    if (saved === now) return;

    // 直接比整段字符串的话，错误里是两大坨文本，看不出差在哪一行
    const a = saved.split("\n");
    const b = now.split("\n");
    const onlyBefore = a.filter((l) => l.startsWith("  ") || (!l.startsWith("#") && !l.startsWith("##") && l && !b.includes(l)));
    const onlyAfter = b.filter((l) => l && !l.startsWith("#") && !a.includes(l));
    expect(
      { 少掉的: onlyBefore, 多出的: onlyAfter },
      `菜单可见性变了。确认是有意的之后：
  UPDATE_NAV_SNAPSHOT=1 npx vitest run lib/nav-visibility.test.ts
并把快照的 diff 一起提交。`,
    ).toEqual({ 少掉的: [], 多出的: [] });
  });

  it("快照不是空的——空快照会让这条卡口永远绿", () => {
    const roles = Object.keys(BACKEND_ROLE_PERMS) as Role[];
    expect(roles.length).toBeGreaterThan(3);
    for (const r of roles) {
      if (r === "AGENT") continue; // 代理端门户本来就只有几项
      expect(visibleFor(r).length, `${r} 一项菜单都看不到，快照就失去意义`).toBeGreaterThan(0);
    }
  });
});
