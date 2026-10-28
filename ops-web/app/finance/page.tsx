"use client";

// 财务管理（OM07）：对账 · 账务分录 · 调整单 · 发票（发票待 OM09 调整时迁走）。
// 2026-10-28 结算六个页签拆到 /settlement（OM08）；调整单从结算单的一个视图升为本页页签（OM07）。
// 旧链接的改写规则在 lib/finance-routes.ts，与 /settlement 共用。
import { Suspense, useEffect } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { FinanceWorkspace } from "@/components/finance/finance-workspace";
import { FINANCE_TAB_KEYS, financeRedirect } from "@/lib/finance-routes";

function FinanceRoute() {
  const sp = useSearchParams();
  const router = useRouter();
  const to = financeRedirect("/finance", new URLSearchParams(sp.toString()));
  useEffect(() => { if (to) router.replace(to); }, [to, router]);
  if (to) return null;
  return <FinanceWorkspace path="/finance" tabKeys={FINANCE_TAB_KEYS} />;
}

export default function FinancePage() {
  return <Suspense fallback={null}><FinanceRoute /></Suspense>;
}
