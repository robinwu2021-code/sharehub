"use client";

// 结算（OM08）：分润规则 · 分润明细 · 分润统计 · 结算单 · 提现审核 · 收款账户。
// 2026-10-28 自 /finance 拆出（一个 URL 只属于一个 L1）；页面本体在 FinanceWorkspace，与财务管理共用。
// 站点分成、分成方分成两页仍在 /operation/*-sharing，由结算的 match 认领。
import { Suspense } from "react";
import { FinanceWorkspace, SETTLEMENT_TAB_KEYS } from "@/components/finance/finance-workspace";

export default function SettlementPage() {
  return <Suspense fallback={null}><FinanceWorkspace path="/settlement" tabKeys={SETTLEMENT_TAB_KEYS} /></Suspense>;
}
