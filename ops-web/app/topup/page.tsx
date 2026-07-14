"use client";

// 充值管理（topup 模块）：充值套餐/充值订单。均为 Phase 2。占位页。
import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { TabHeader } from "@/components/ui/tab-header";
import { EmptyState } from "@/components/ui/misc";

const TABS = [
  { key: "packages", label: "充值套餐", phase: 2 as const },
  { key: "orders", label: "充值订单", phase: 2 as const },
];

function Inner() {
  const sp = useSearchParams();
  const qTab = sp.get("tab");
  const [tab, setTab] = useState(TABS.some((t) => t.key === qTab) ? (qTab as string) : TABS[0].key);
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) setTab(qTab); }, [qTab]);
  const current = TABS.find((t) => t.key === tab);
  return (
    <div>
      <TabHeader tabs={TABS} value={tab} onChange={setTab} />
      <EmptyState title={current?.label ?? "充值管理"} desc="功能已在导航体系登记，随交付分期解锁。" />
    </div>
  );
}

export default function TopupPage() {
  return <Suspense fallback={null}><Inner /></Suspense>;
}
