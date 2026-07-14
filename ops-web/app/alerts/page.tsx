"use client";

// 告警中心（alert 模块）：记录 / 通知 / 告警码 / 通知规则 / 设备故障。
// 均为 Phase 2（导航仅作标识，可正常点击）。演示态内容页（本地样本数据）。
// 工单管理在 /work-orders（同域另一模块）。
import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { TabHeader } from "@/components/ui/tab-header";
import { MockTabView } from "@/components/ui/mock-view";
import { type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";

const TABS = [
  { key: "records", label: "告警记录", phase: 2 as const },
  { key: "notifications", label: "告警通知", phase: 2 as const },
  { key: "codes", label: "告警码", phase: 2 as const },
  { key: "rules", label: "通知规则", phase: 2 as const },
  { key: "faults", label: "设备故障", phase: 2 as const },
];

const SEV: Record<string, [string, "danger" | "warning" | "muted"]> = {
  P0: ["紧急", "danger"], P1: ["重要", "warning"], P2: ["提示", "muted"],
};

interface Rec { id: string; code: string; device: string; site: string; sev: keyof typeof SEV; status: "OPEN" | "ACK" | "RESOLVED"; at: string; }
const REC_ROWS: Rec[] = [
  { id: "AL-9001", code: "E-OFFLINE", device: "CAB-0231", site: "陆家嘴中心", sev: "P0", status: "OPEN", at: "07-14 09:31" },
  { id: "AL-9000", code: "E-SLOT-JAM", device: "CAB-0184", site: "虹桥枢纽 T2", sev: "P1", status: "ACK", at: "07-14 09:05" },
  { id: "AL-8998", code: "E-TEMP-HIGH", device: "CAB-0102", site: "南京路步行街", sev: "P1", status: "OPEN", at: "07-14 08:40" },
  { id: "AL-8990", code: "E-LOW-BAT", device: "CAB-0077", site: "北外滩来福士", sev: "P2", status: "RESOLVED", at: "07-13 22:12" },
];
const REC_STATUS: Record<Rec["status"], [string, "warning" | "success" | "muted"]> = {
  OPEN: ["未处理", "warning"], ACK: ["已认领", "muted"], RESOLVED: ["已恢复", "success"],
};
const REC_COLS: Column<Rec>[] = [
  { header: "告警ID", cell: (r) => <span className="font-medium">{r.id}</span> },
  { header: "告警码", cell: (r) => <Badge tone="outline">{r.code}</Badge> },
  { header: "设备", cell: (r) => r.device },
  { header: "站点", cell: (r) => <span className="text-muted-foreground">{r.site}</span> },
  { header: "级别", cell: (r) => <Badge tone={SEV[r.sev][1]}>{SEV[r.sev][0]}</Badge> },
  { header: "状态", cell: (r) => <Badge tone={REC_STATUS[r.status][1]}>{REC_STATUS[r.status][0]}</Badge> },
  { header: "时间", cell: (r) => <span className="text-muted-foreground">{r.at}</span> },
];

interface Noti { id: string; channel: string; target: string; alert: string; result: "SENT" | "FAILED"; at: string; }
const NOTI_ROWS: Noti[] = [
  { id: "N1", channel: "短信", target: "运维值班组", alert: "AL-9001", result: "SENT", at: "07-14 09:31" },
  { id: "N2", channel: "企业微信", target: "华东运维", alert: "AL-9000", result: "SENT", at: "07-14 09:05" },
  { id: "N3", channel: "邮件", target: "ops@sharehub", alert: "AL-8998", result: "FAILED", at: "07-14 08:41" },
  { id: "N4", channel: "Webhook", target: "PagerDuty", alert: "AL-8998", result: "SENT", at: "07-14 08:41" },
];
const NOTI_COLS: Column<Noti>[] = [
  { header: "渠道", cell: (r) => <Badge tone="outline">{r.channel}</Badge> },
  { header: "接收方", cell: (r) => <span className="font-medium">{r.target}</span> },
  { header: "关联告警", cell: (r) => r.alert },
  { header: "结果", cell: (r) => r.result === "SENT" ? <Badge tone="success">已送达</Badge> : <Badge tone="danger">失败</Badge> },
  { header: "时间", cell: (r) => <span className="text-muted-foreground">{r.at}</span> },
];

interface Code { id: string; code: string; name: string; sev: keyof typeof SEV; suggestion: string; }
const CODE_ROWS: Code[] = [
  { id: "C1", code: "E-OFFLINE", name: "设备离线", sev: "P0", suggestion: "检查网络/供电" },
  { id: "C2", code: "E-SLOT-JAM", name: "仓位卡阻", sev: "P1", suggestion: "现场巡检清理" },
  { id: "C3", code: "E-TEMP-HIGH", name: "温度过高", sev: "P1", suggestion: "降载并排查散热" },
  { id: "C4", code: "E-LOW-BAT", name: "电量偏低", sev: "P2", suggestion: "调拨补货" },
];
const CODE_COLS: Column<Code>[] = [
  { header: "告警码", cell: (r) => <Badge tone="outline">{r.code}</Badge> },
  { header: "名称", cell: (r) => <span className="font-medium">{r.name}</span> },
  { header: "默认级别", cell: (r) => <Badge tone={SEV[r.sev][1]}>{SEV[r.sev][0]}</Badge> },
  { header: "处置建议", cell: (r) => <span className="text-muted-foreground">{r.suggestion}</span> },
];

interface Rule { id: string; name: string; cond: string; channel: string; on: boolean; }
const RULE_ROWS: Rule[] = [
  { id: "R1", name: "紧急告警即时短信", cond: "级别 = 紧急", channel: "短信 + 企业微信", on: true },
  { id: "R2", name: "重复离线升级", cond: "10min 内 ≥3 次离线", channel: "电话", on: true },
  { id: "R3", name: "夜间静默", cond: "23:00–07:00 且级别 = 提示", channel: "仅站内", on: true },
  { id: "R4", name: "旧版邮件抄送", cond: "全部告警", channel: "邮件", on: false },
];
const RULE_COLS: Column<Rule>[] = [
  { header: "规则名", cell: (r) => <span className="font-medium">{r.name}</span> },
  { header: "触发条件", cell: (r) => <span className="text-muted-foreground">{r.cond}</span> },
  { header: "通知渠道", cell: (r) => r.channel },
  { header: "状态", cell: (r) => r.on ? <Badge tone="success">启用</Badge> : <Badge tone="muted">停用</Badge> },
];

interface Fault { id: string; device: string; site: string; type: string; status: "REPAIRING" | "PENDING" | "FIXED"; at: string; }
const FAULT_ROWS: Fault[] = [
  { id: "FT-501", device: "CAB-0231", site: "陆家嘴中心", type: "主板离线", status: "REPAIRING", at: "07-14 09:31" },
  { id: "FT-500", device: "CAB-0184", site: "虹桥枢纽 T2", type: "仓位电机卡阻", status: "PENDING", at: "07-14 09:05" },
  { id: "FT-498", device: "CAB-0044", site: "静安嘉里中心", type: "屏幕黑屏", status: "FIXED", at: "07-13 16:20" },
];
const FAULT_STATUS: Record<Fault["status"], [string, "warning" | "muted" | "success"]> = {
  REPAIRING: ["维修中", "warning"], PENDING: ["待派单", "muted"], FIXED: ["已修复", "success"],
};
const FAULT_COLS: Column<Fault>[] = [
  { header: "故障单", cell: (r) => <span className="font-medium">{r.id}</span> },
  { header: "设备", cell: (r) => r.device },
  { header: "站点", cell: (r) => <span className="text-muted-foreground">{r.site}</span> },
  { header: "故障类型", cell: (r) => r.type },
  { header: "状态", cell: (r) => <Badge tone={FAULT_STATUS[r.status][1]}>{FAULT_STATUS[r.status][0]}</Badge> },
  { header: "上报时间", cell: (r) => <span className="text-muted-foreground">{r.at}</span> },
];

function Body({ tab }: { tab: string }) {
  switch (tab) {
    case "notifications":
      return <MockTabView stats={[{ label: "今日通知", value: 156 }, { label: "已送达", value: 148 }, { label: "失败", value: 8, tone: "down" }, { label: "送达率", value: "94.9%" }]} columns={NOTI_COLS} rows={NOTI_ROWS} />;
    case "codes":
      return <MockTabView stats={[{ label: "告警码", value: 24 }, { label: "紧急级", value: 5, tone: "down" }, { label: "重要级", value: 11 }, { label: "提示级", value: 8 }]} columns={CODE_COLS} rows={CODE_ROWS} />;
    case "rules":
      return <MockTabView stats={[{ label: "通知规则", value: 9 }, { label: "启用", value: 7 }, { label: "停用", value: 2 }, { label: "覆盖渠道", value: 4 }]} columns={RULE_COLS} rows={RULE_ROWS} />;
    case "faults":
      return <MockTabView stats={[{ label: "待修故障", value: 11 }, { label: "维修中", value: 4, tone: "down" }, { label: "今日修复", value: 6 }, { label: "平均时长", value: "3.2h" }]} columns={FAULT_COLS} rows={FAULT_ROWS} />;
    default:
      return <MockTabView stats={[{ label: "今日告警", value: 42 }, { label: "未处理", value: 9, tone: "down" }, { label: "紧急", value: 3, tone: "down" }, { label: "平均恢复", value: "26m" }]} columns={REC_COLS} rows={REC_ROWS} />;
  }
}

function Inner() {
  const sp = useSearchParams();
  const qTab = sp.get("tab");
  const [tab, setTab] = useState(TABS.some((t) => t.key === qTab) ? (qTab as string) : TABS[0].key);
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) setTab(qTab); }, [qTab]);
  return (
    <div>
      <TabHeader tabs={TABS} value={tab} onChange={setTab} />
      <Body tab={tab} />
    </div>
  );
}

export default function AlertsPage() {
  return <Suspense fallback={null}><Inner /></Suspense>;
}
