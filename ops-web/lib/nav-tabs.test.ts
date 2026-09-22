// navTabs 单测：页内 tab 的**名字与权限**以 nav.ts 为准。
// 这三件事各自对应一个真实缺陷（见 navTabs 注释），所以都留了用例。
import { describe, it, expect } from "vitest";
import { navTabs, NAV, leafParts } from "./nav";
import { can } from "./permissions";
import type { Role } from "./auth";

describe("navTabs 取名", () => {
  it("名字来自菜单，不是页面自己写的那一份", () => {
    // 整理前 /finance 页面把这个 tab 叫「提现」，菜单叫「提现审核」——
    // 同一个功能两个名字，改一处不改另一处没有任何东西会报错
    const [tab] = navTabs("/finance", ["withdrawals"], "ADMIN");
    expect(tab.label).toBe("提现审核");
  });

  it("保序：按传入顺序返回，不按菜单顺序", () => {
    const keys = navTabs("/finance", ["settlements", "rules"], "ADMIN").map((t) => t.key);
    expect(keys).toEqual(["settlements", "rules"]);
  });

  it("带出 phase（由 TabHeader 决定是否隐藏，这里不替它做主）", () => {
    const [tab] = navTabs("/finance", ["ledger"], "ADMIN");
    expect(tab.phase).toBe(2);
  });

  it("非菜单叶的页内子视图：自带 label，不抛错", () => {
    const [tab] = navTabs("/finance", [{ key: "__sub", label: "按代理商看" }], "ADMIN");
    expect(tab).toEqual({ key: "__sub", label: "按代理商看" });
  });

  it("菜单里没登记又没自带 label：开发期抛错（漏登记 = 这个功能菜单里进不去）", () => {
    expect(() => navTabs("/finance", ["no-such-tab"], "ADMIN")).toThrow(/未在 nav.ts 登记/);
  });
});

describe("navTabs 判权（与菜单同一口径）", () => {
  // 整理前 TABS 是页面里写死的数组，完全不判权：没有 finance:withdrawal:read
  // 的角色照样看得到、点得动「提现」tab，只靠接口 403 兜底。
  const leafPerm = (path: string, tab: string) =>
    NAV.flatMap((s) => s.children ?? [])
      .find((l) => { const p = leafParts(l.href); return p.path === path && p.tab === tab; })?.perm;

  it("无权限的 tab 不渲染", () => {
    const perm = leafPerm("/finance", "withdrawals")!;
    const roles: Role[] = ["ADMIN", "OPERATOR", "FINANCE", "SUPPORT", "AGENT"];
    for (const role of roles) {
      const visible = navTabs("/finance", ["withdrawals"], role).length > 0;
      expect(visible, `${role}`).toBe(can(role, perm));
    }
  });

  it("至少有一个角色被挡住（否则这条用例是空转的）", () => {
    const roles: Role[] = ["ADMIN", "OPERATOR", "FINANCE", "SUPPORT", "AGENT"];
    const blocked = roles.filter((r) => navTabs("/finance", ["withdrawals"], r).length === 0);
    expect(blocked.length).toBeGreaterThan(0);
  });
});
