"use client";

// 结算（OM08）：分润规则 · 分润明细 · 分润统计 · 结算单 · 付款任务 · 收款账户。
// 2026-10-28 自 /finance 拆出（一个 URL 只属于一个 L1）；页面本体在 FinanceWorkspace，与财务管理共用。
// 站点分成、分成方分成两页仍在 /operation/*-sharing，由结算的 match 认领。
// 旧的「结算单 › 调整项」视图链接转到财务管理 › 调整单（规则在 lib/finance-routes.ts）。
import { Suspense, useEffect } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { FinanceWorkspace } from "@/components/finance/finance-workspace";
import { SETTLEMENT_TAB_KEYS, financeRedirect } from "@/lib/finance-routes";

function SettlementRoute() {
  const sp = useSearchParams();
  const router = useRouter();
  const to = financeRedirect("/settlement", new URLSearchParams(sp.toString()));
  useEffect(() => { if (to) router.replace(to); }, [to, router]);
  if (to) return null;
  return <FinanceWorkspace path="/settlement" tabKeys={SETTLEMENT_TAB_KEYS} />;
}

export default function SettlementPage() {
  return <Suspense fallback={null}><SettlementRoute /></Suspense>;
}
