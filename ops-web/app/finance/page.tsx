"use client";

// 财务管理（OM07）：账务分录 · 对账 · 发票（发票待 OM09 调整时迁走）。
// 2026-10-28 结算六个页签拆到 /settlement（OM08）。旧链接 /finance?tab=settlements… 仍然散在各处：
// 收藏夹、后端清退门禁给的「去处理」（AgentExitServiceImpl）、已发出去的通知 ——
// 这里把它们原样转到 /settlement（保留 no / payee / view 等参数），不让人点进一个没有那个页签的页面。
import { Suspense, useEffect } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { FinanceWorkspace, FINANCE_TAB_KEYS, SETTLEMENT_TAB_KEYS } from "@/components/finance/finance-workspace";

const MOVED = new Set<string>(SETTLEMENT_TAB_KEYS);

function FinanceRoute() {
  const sp = useSearchParams();
  const router = useRouter();
  const tab = sp.get("tab");
  const moved = !!tab && MOVED.has(tab);
  useEffect(() => {
    if (moved) router.replace(`/settlement?${sp.toString()}`);
  }, [moved, router, sp]);
  if (moved) return null;
  return <FinanceWorkspace path="/finance" tabKeys={FINANCE_TAB_KEYS} />;
}

export default function FinancePage() {
  return <Suspense fallback={null}><FinanceRoute /></Suspense>;
}
