import { describe, it, expect } from "vitest";
import { financeRedirect, SETTLEMENT_TAB_KEYS, FINANCE_TAB_KEYS } from "./finance-routes";
import { missingTabs } from "./nav";
import { BACKEND_ROLE_PERMS } from "./permissions";

const go = (path: "/finance" | "/settlement", q: string) => financeRedirect(path, new URLSearchParams(q));

describe("financeRedirect：结算 / 财务两页的旧链接改写", () => {
  it("/finance 上的结算页签整体换到 /settlement，参数保留", () => {
    expect(go("/finance", "tab=withdrawals&payee=AG001")).toBe("/settlement?tab=withdrawals&payee=AG001");
    expect(go("/finance", "tab=settlements&no=STL700")).toBe("/settlement?tab=settlements&no=STL700");
  });
  it("调整项视图（结算单 ?view=adjustments）换到财务管理的调整单页签，两条路径都认", () => {
    expect(go("/finance", "tab=settlements&view=adjustments")).toBe("/finance?tab=adjustments");
    expect(go("/settlement", "tab=settlements&view=adjustments")).toBe("/finance?tab=adjustments");
  });
  it("本页自己的页签、裸路径不改写", () => {
    expect(go("/finance", "tab=reconcile")).toBeNull();
    expect(go("/finance", "")).toBeNull();
    expect(go("/settlement", "tab=settlements&no=STL700")).toBeNull();
  });
});

describe("两页的页签都在菜单里登记（nav-page-tabs 的扫描看不到这两页）", () => {
  // nav-page-tabs.test.ts 扫的是 app/**/page.tsx 里的 `useNavTabs("<字面量路径>", 常量)`；
  // 两页把路径作为 prop 传给共用的 FinanceWorkspace，扫描匹配不到，所以在这里逐页补问一次。
  // 漏登记的表现是整页白屏（navTabs 开发期抛错），不是少一个页签。
  const admin = { role: "ADMIN" as const, perms: BACKEND_ROLE_PERMS.ADMIN };
  it("/settlement", () => {
    expect(missingTabs("/settlement", SETTLEMENT_TAB_KEYS, admin)).toEqual([]);
  });
  it("/finance", () => {
    expect(missingTabs("/finance", FINANCE_TAB_KEYS, admin)).toEqual([]);
  });
});
