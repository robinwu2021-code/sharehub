"use client";

// 合作伙伴（partner 模块，四级主入口 platform→operator→merchant→store）：
// 商户 / 代理商档案 / 入驻审核 / 运营商。演示态内容页（本地样本数据）。
import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { TabHeader } from "@/components/ui/tab-header";
import { MockTabView } from "@/components/ui/mock-view";
import { type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";

const TABS = [
  { key: "merchants", label: "商户", phase: 1 as const },
  { key: "agents", label: "代理商档案", phase: 1 as const },
  { key: "onboarding", label: "入驻审核", phase: 2 as const },
  { key: "operators", label: "运营商", phase: 3 as const },
];

interface Merchant { id: string; name: string; category: string; sites: number; revshare: string; status: "ACTIVE" | "PAUSED"; }
const MERCHANT_ROWS: Merchant[] = [
  { id: "MC-1001", name: "星巴克 陆家嘴中心店", category: "连锁咖啡", sites: 3, revshare: "55%", status: "ACTIVE" },
  { id: "MC-1002", name: "海底捞 南京路店", category: "餐饮", sites: 1, revshare: "50%", status: "ACTIVE" },
  { id: "MC-1003", name: "万达影城 五角场", category: "影院", sites: 2, revshare: "60%", status: "ACTIVE" },
  { id: "MC-1004", name: "全家便利店（多点）", category: "便利店", sites: 12, revshare: "45%", status: "PAUSED" },
];
const MERCHANT_COLS: Column<Merchant>[] = [
  { header: "商户", cell: (r) => <span className="font-medium">{r.name}</span> },
  { header: "编号", cell: (r) => <span className="text-muted-foreground">{r.id}</span> },
  { header: "行业", cell: (r) => <Badge tone="outline">{r.category}</Badge> },
  { header: "站点数", cell: (r) => <span className="tabular-nums">{r.sites}</span> },
  { header: "分成", cell: (r) => <span className="tabular-nums">{r.revshare}</span> },
  { header: "状态", cell: (r) => r.status === "ACTIVE" ? <Badge tone="success">合作中</Badge> : <Badge tone="muted">暂停</Badge> },
];

interface Agent { id: string; name: string; region: string; devices: number; commission: string; status: "ACTIVE" | "PAUSED"; }
const AGENT_ROWS: Agent[] = [
  { id: "AG-201", name: "华东一区 · 张伟", region: "上海浦东", devices: 320, commission: "8%", status: "ACTIVE" },
  { id: "AG-202", name: "华东二区 · 李娜", region: "上海浦西", devices: 285, commission: "8%", status: "ACTIVE" },
  { id: "AG-203", name: "苏南 · 王强", region: "苏州/无锡", devices: 176, commission: "7.5%", status: "ACTIVE" },
  { id: "AG-204", name: "浙北 · 陈静", region: "杭州", devices: 92, commission: "7%", status: "PAUSED" },
];
const AGENT_COLS: Column<Agent>[] = [
  { header: "代理商", cell: (r) => <span className="font-medium">{r.name}</span> },
  { header: "编号", cell: (r) => <span className="text-muted-foreground">{r.id}</span> },
  { header: "区域", cell: (r) => r.region },
  { header: "管辖设备", cell: (r) => <span className="tabular-nums">{r.devices}</span> },
  { header: "分润比例", cell: (r) => <span className="tabular-nums">{r.commission}</span> },
  { header: "状态", cell: (r) => r.status === "ACTIVE" ? <Badge tone="success">合作中</Badge> : <Badge tone="muted">暂停</Badge> },
];

interface Onb { id: string; applicant: string; type: string; submitted: string; status: "PENDING" | "APPROVED" | "REJECTED"; }
const ONB_ROWS: Onb[] = [
  { id: "OB-77", applicant: "喜茶 静安嘉里店", type: "商户", submitted: "07-14 08:20", status: "PENDING" },
  { id: "OB-76", applicant: "华东三区 · 赵敏", type: "代理商", submitted: "07-13 14:02", status: "PENDING" },
  { id: "OB-75", applicant: "瑞幸咖啡（连锁）", type: "商户", submitted: "07-12 10:31", status: "APPROVED" },
  { id: "OB-74", applicant: "个体 · 刘某", type: "代理商", submitted: "07-11 16:45", status: "REJECTED" },
];
const ONB_STATUS: Record<Onb["status"], [string, "warning" | "success" | "danger"]> = {
  PENDING: ["待审核", "warning"], APPROVED: ["已通过", "success"], REJECTED: ["已驳回", "danger"],
};
const ONB_COLS: Column<Onb>[] = [
  { header: "申请单", cell: (r) => <span className="font-medium">{r.id}</span> },
  { header: "申请方", cell: (r) => r.applicant },
  { header: "类型", cell: (r) => <Badge tone="outline">{r.type}</Badge> },
  { header: "提交时间", cell: (r) => <span className="text-muted-foreground">{r.submitted}</span> },
  { header: "状态", cell: (r) => <Badge tone={ONB_STATUS[r.status][1]}>{ONB_STATUS[r.status][0]}</Badge> },
];

interface Operator { id: string; name: string; country: string; merchants: number; devices: number; status: "ACTIVE" | "ONBOARDING"; }
const OPERATOR_ROWS: Operator[] = [
  { id: "OP-01", name: "简电自营 · 中国", country: "🇨🇳 中国", merchants: 412, devices: 5240, status: "ACTIVE" },
  { id: "OP-02", name: "ShareHub HK", country: "🇭🇰 香港", merchants: 58, devices: 690, status: "ACTIVE" },
  { id: "OP-03", name: "ShareHub SG", country: "🇸🇬 新加坡", merchants: 21, devices: 240, status: "ONBOARDING" },
];
const OPERATOR_COLS: Column<Operator>[] = [
  { header: "运营商", cell: (r) => <span className="font-medium">{r.name}</span> },
  { header: "国家/地区", cell: (r) => r.country },
  { header: "商户数", cell: (r) => <span className="tabular-nums">{r.merchants}</span> },
  { header: "设备数", cell: (r) => <span className="tabular-nums">{r.devices}</span> },
  { header: "状态", cell: (r) => r.status === "ACTIVE" ? <Badge tone="success">运营中</Badge> : <Badge tone="warning">接入中</Badge> },
];

function Body({ tab }: { tab: string }) {
  switch (tab) {
    case "agents":
      return <MockTabView stats={[{ label: "代理商", value: 26 }, { label: "合作中", value: 23 }, { label: "管辖设备", value: "4,120" }, { label: "本月分润", value: "¥286K" }]} columns={AGENT_COLS} rows={AGENT_ROWS} />;
    case "onboarding":
      return <MockTabView stats={[{ label: "待审核", value: 7, tone: "down" }, { label: "本周通过", value: 12 }, { label: "本周驳回", value: 3 }, { label: "平均时效", value: "6.4h" }]} columns={ONB_COLS} rows={ONB_ROWS} />;
    case "operators":
      return <MockTabView stats={[{ label: "运营商", value: 3 }, { label: "覆盖国家", value: 3 }, { label: "商户总数", value: 491 }, { label: "设备总数", value: "6,170" }]} columns={OPERATOR_COLS} rows={OPERATOR_ROWS} />;
    default:
      return <MockTabView stats={[{ label: "签约商户", value: 412 }, { label: "合作中", value: 398 }, { label: "覆盖站点", value: "1,860" }, { label: "本月新签", value: 34 }]} columns={MERCHANT_COLS} rows={MERCHANT_ROWS} />;
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

export default function PartnersPage() {
  return <Suspense fallback={null}><Inner /></Suspense>;
}
