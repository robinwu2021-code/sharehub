"use client";

// 消息中心（msg 模块）：发送记录/客服会话/黑名单/消息模板。
// 消息模板为 P1（承接老「通知模板」）；其余 P2。占位页。
import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { TabHeader } from "@/components/ui/tab-header";
import { EmptyState } from "@/components/ui/misc";

const TABS = [
  { key: "records", label: "发送记录", phase: 2 as const },
  { key: "sessions", label: "客服会话", phase: 2 as const },
  { key: "blocked", label: "黑名单", phase: 2 as const },
  { key: "templates", label: "消息模板", phase: 1 as const },
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
      <EmptyState title={current?.label ?? "消息中心"} desc="功能已在导航体系登记，随交付分期解锁。" />
    </div>
  );
}

export default function MessagingPage() {
  return <Suspense fallback={null}><Inner /></Suspense>;
}
