"use client";

// 基础运营（operations 模块）：公告/App 版本/电池统计/银行账户/反馈/汇率。
// 均为 Phase 2；P1 下由 PhaseGuard 拦截。占位页，随分期解锁逐步落地。
import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { TabHeader } from "@/components/ui/tab-header";
import { EmptyState } from "@/components/ui/misc";

const TABS = [
  { key: "announcements", label: "运营公告", phase: 2 as const },
  { key: "app-versions", label: "App 版本", phase: 2 as const },
  { key: "battery-stats", label: "电池统计", phase: 2 as const },
  { key: "bank-accounts", label: "银行账户", phase: 2 as const },
  { key: "feedback", label: "用户反馈", phase: 2 as const },
  { key: "fx", label: "汇率与币种", phase: 2 as const },
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
      <EmptyState title={current?.label ?? "基础运营"} desc="功能已在导航体系登记，随交付分期解锁。" />
    </div>
  );
}

export default function OperationsPage() {
  return <Suspense fallback={null}><Inner /></Suspense>;
}
