// navTabs 单测：页内 tab 的**名字与权限**以 nav.ts 为准。
// 这三件事各自对应一个真实缺陷（见 navTabs 注释），所以都留了用例。
import { describe, it, expect } from "vitest";
import { navTabs, NAV, leafParts } from "./nav";
import { roleHas } from "./permissions";
import type { Role } from "./auth";

/** 与 lib/finance-routes.ts 的 SETTLEMENT_TAB_KEYS 同一组，用于「主属 section」判定 */
const SETTLEMENT_KEYS = ["rules", "records", "summary", "settlements",
  "withdrawals", "payout-accounts"] as const; // 2026-10-28 自 /finance 拆到 /settlement（OM08）

describe("navTabs 取名", () => {
  it("名字来自菜单，不是页面自己写的那一份", () => {
    // 整理前 /finance 页面把这个 tab 叫「提现」，菜单叫「提现审核」——
    // 同一个功能两个名字，改一处不改另一处没有任何东西会报错
    const [tab] = navTabs("/settlement", ["withdrawals"], "ADMIN");
    expect(tab.label).toBe("提现审核");
  });

  it("保序：按传入顺序返回，不按菜单顺序", () => {
    const keys = navTabs("/settlement", ["settlements", "rules"], "ADMIN").map((t) => t.key);
    expect(keys).toEqual(["settlements", "rules"]);
  });

  it("同一 href 在多个菜单登记时，取**本页主属 section** 的名字", () => {
    // /settlement?tab=settlements 在「我的经营 › 我的结算」（商家门户）和「结算 › 结算单」
    // 各有一条（2026-10-28 前还有「代理商管理 › 代理收益结算」，已随 V148 撤销）。
    // 结算页请求的 6 个 tab 绝大多数属于结算域，所以该取「结算单」；
    // 按 NAV 顺序取第一个会拿到门户的「我的结算」——在结算页上是错的名字。
    const [tab] = navTabs("/settlement", [...SETTLEMENT_KEYS], "ADMIN");
    expect(navTabs("/settlement", [...SETTLEMENT_KEYS], "ADMIN").find((t) => t.key === "settlements")!.label)
      .toBe("结算单");
    expect(tab.key).toBe("rules");
  });

  it("带出 phase（由 TabHeader 决定是否隐藏，这里不替它做主）", () => {
    const [tab] = navTabs("/finance", ["ledger"], "ADMIN");
    expect(tab.phase).toBe(2);
  });

  it("ready 的叶子不带 phase —— 与菜单的 isLeafLocked 同口径，否则「菜单能点、页签条没有」", () => {
    // 发票 phase 2 + ready；账务分录 phase 2 未 ready
    const tabs = navTabs("/finance", ["ledger", "invoices"], "ADMIN");
    expect(tabs.find((t) => t.key === "invoices")!.phase).toBeUndefined();
    expect(tabs.find((t) => t.key === "ledger")!.phase).toBe(2);
    // 商家管理两片 ready 叶（OM01）
    const agentTabs = navTabs("/agents", ["profiles", "business", "performance", "exits"], "ADMIN", "profiles");
    expect(agentTabs.find((t) => t.key === "business")!.phase).toBeUndefined();
    expect(agentTabs.find((t) => t.key === "exits")!.phase).toBeUndefined();
    expect(agentTabs.find((t) => t.key === "performance")!.phase).toBe(2);
  });

  it("非菜单叶的页内子视图：自带 label，不抛错", () => {
    const [tab] = navTabs("/settlement", [{ key: "__sub", label: "按代理商看" }], "ADMIN");
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
    const perm = leafPerm("/settlement", "withdrawals")!;
    const roles: Role[] = ["ADMIN", "OPERATOR", "FINANCE", "SUPPORT", "AGENT"];
    for (const role of roles) {
      const visible = navTabs("/settlement", ["withdrawals"], role).length > 0;
      // roleHas = 角色视角的判权（按 BACKEND_ROLE_PERMS 展开）。
      // can() 现在收的是**后端下发的 perms**，不是 role —— 语义没变，入口换了（D6a）。
      expect(visible, `${role}`).toBe(roleHas(role, perm));
    }
  });

  it("至少有一个角色被挡住（否则这条用例是空转的）", () => {
    const roles: Role[] = ["ADMIN", "OPERATOR", "FINANCE", "SUPPORT", "AGENT"];
    const blocked = roles.filter((r) => navTabs("/settlement", ["withdrawals"], r).length === 0);
    expect(blocked.length).toBeGreaterThan(0);
  });
});
