// 结算（/settlement）与财务管理（/finance）两页的页签归属，以及旧链接改写。
//
// 一个 URL 只属于一个 L1（findActiveSection 按路径前缀定归属），所以两个 L1 各占一条路径：
//   2026-10-28 OM08：结算六个页签自 /finance 拆到 /settlement；
//   2026-10-28 OM07：调整单从「结算单」页签里的一个视图（?view=adjustments）升为财务管理的页签。
// 旧链接仍散在各处 —— 收藏、后端清退门禁给的「去处理」（AgentExitServiceImpl 出 /finance?tab=…）、
// 已发出的通知 —— 改写集中在这里，两页都调它，规则只写一处。

/** 结算页签（菜单顺序）。 */
export const SETTLEMENT_TAB_KEYS = ["rules", "records", "summary", "settlements",
  "withdrawals", "payout-accounts"] as const;
/** 财务管理页签（菜单顺序：对账 → 账务 → 发票；发票待 OM09 迁走）。 */
export const FINANCE_TAB_KEYS = ["reconcile", "ledger", "adjustments", "invoices"] as const;

const MOVED_TO_SETTLEMENT = new Set<string>(SETTLEMENT_TAB_KEYS);

/**
 * 当前地址该不该换到别处：返回目标 URL，不用换返回 null。
 * 参数原样保留（no / payee 等），只改路径与页签。
 */
export function financeRedirect(path: "/finance" | "/settlement", sp: URLSearchParams): string | null {
  const tab = sp.get("tab");
  if (tab === "settlements" && sp.get("view") === "adjustments") {
    const q = new URLSearchParams(sp);
    q.set("tab", "adjustments");
    q.delete("view");
    return `/finance?${q.toString()}`;
  }
  if (path === "/finance" && tab && MOVED_TO_SETTLEMENT.has(tab)) return `/settlement?${sp.toString()}`;
  return null;
}
