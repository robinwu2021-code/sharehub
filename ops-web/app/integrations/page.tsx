"use client";

// 集成中心（integration 模块，仅 ADMIN）：Neargo 支付/OpenAPI/POS/Kiosk/地图/KYC。
// Neargo P2，其余 P3。占位页，随分期解锁。
import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { TabHeader } from "@/components/ui/tab-header";
import { EmptyState } from "@/components/ui/misc";

const TABS = [
  { key: "neargo", label: "Neargo 支付", phase: 2 as const },
  { key: "openapi", label: "OpenAPI 应用", phase: 3 as const },
  { key: "pos", label: "POS 对接", phase: 3 as const },
  { key: "kiosk", label: "Kiosk 自助机", phase: 3 as const },
  { key: "maps", label: "地图服务", phase: 3 as const },
  { key: "kyc", label: "第三方 KYC", phase: 3 as const },
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
      <EmptyState title={current?.label ?? "集成中心"} desc="功能已在导航体系登记，随交付分期解锁。" />
    </div>
  );
}

export default function IntegrationsPage() {
  return <Suspense fallback={null}><Inner /></Suspense>;
}
