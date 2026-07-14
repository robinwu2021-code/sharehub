"use client";

// 合作伙伴（partner 模块，四级主入口）：商户/代理商档案/运营商/入驻审核。
// 商户·代理商档案为 P1；运营商 P3、入驻审核 P2。占位页，P1 tab 可访问。
import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { TabHeader } from "@/components/ui/tab-header";
import { EmptyState } from "@/components/ui/misc";

const TABS = [
  { key: "merchants", label: "商户", phase: 1 as const },
  { key: "agents", label: "代理商档案", phase: 1 as const },
  { key: "onboarding", label: "入驻审核", phase: 2 as const },
  { key: "operators", label: "运营商", phase: 3 as const },
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
      <EmptyState title={current?.label ?? "合作伙伴"} desc="四级账户体系入口（platform→operator→merchant→store）。占位页，随分期落地。" />
    </div>
  );
}

export default function PartnersPage() {
  return <Suspense fallback={null}><Inner /></Suspense>;
}
