"use client";

// 告警中心（alert 模块）：记录/通知/告警码/通知规则/设备故障。均为 Phase 2。
// 工单管理在 /work-orders（已存在）。占位页，随分期解锁逐步落地。
import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { TabHeader } from "@/components/ui/tab-header";
import { EmptyState } from "@/components/ui/misc";

const TABS = [
  { key: "records", label: "告警记录", phase: 2 as const },
  { key: "notifications", label: "告警通知", phase: 2 as const },
  { key: "codes", label: "告警码", phase: 2 as const },
  { key: "rules", label: "通知规则", phase: 2 as const },
  { key: "faults", label: "设备故障", phase: 2 as const },
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
      <EmptyState title={current?.label ?? "告警中心"} desc="功能已在导航体系登记，随交付分期解锁。" />
    </div>
  );
}

export default function AlertsPage() {
  return <Suspense fallback={null}><Inner /></Suspense>;
}
