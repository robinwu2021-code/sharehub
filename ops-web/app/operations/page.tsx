"use client";

// 基础运营（operations 模块）：公告 / App 版本 / 电池统计 / 银行账户 / 反馈 / 汇率。
// 均为 Phase 2（导航仅作标识，可正常点击）。演示态内容页（本地样本数据）。
import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { TabHeader } from "@/components/ui/tab-header";
import { MockTabView } from "@/components/ui/mock-view";
import { type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";

const TABS = [
  { key: "announcements", label: "运营公告", phase: 2 as const },
  { key: "app-versions", label: "App 版本", phase: 2 as const },
  { key: "battery-stats", label: "电池统计", phase: 2 as const },
  { key: "bank-accounts", label: "银行账户", phase: 2 as const },
  { key: "feedback", label: "用户反馈", phase: 2 as const },
  { key: "fx", label: "汇率与币种", phase: 2 as const },
];

interface Ann { id: string; title: string; audience: string; status: "PUBLISHED" | "DRAFT" | "SCHEDULED"; at: string; reads: number; }
const ANN_ROWS: Ann[] = [
  { id: "A1", title: "暑期租借优惠上线通知", audience: "全部用户", status: "PUBLISHED", at: "07-13 10:00", reads: 8421 },
  { id: "A2", title: "部分站点维护公告", audience: "华东区", status: "PUBLISHED", at: "07-12 15:30", reads: 2210 },
  { id: "A3", title: "新版隐私政策更新", audience: "全部用户", status: "SCHEDULED", at: "07-16 09:00", reads: 0 },
  { id: "A4", title: "商户结算周期调整", audience: "商户", status: "DRAFT", at: "-", reads: 0 },
];
const ANN_STATUS: Record<Ann["status"], [string, "success" | "warning" | "muted"]> = {
  PUBLISHED: ["已发布", "success"], SCHEDULED: ["定时", "warning"], DRAFT: ["草稿", "muted"],
};
const ANN_COLS: Column<Ann>[] = [
  { header: "标题", cell: (r) => <span className="font-medium">{r.title}</span> },
  { header: "受众", cell: (r) => r.audience },
  { header: "状态", cell: (r) => <Badge tone={ANN_STATUS[r.status][1]}>{ANN_STATUS[r.status][0]}</Badge> },
  { header: "发布时间", cell: (r) => <span className="text-muted-foreground">{r.at}</span> },
  { header: "阅读", cell: (r) => <span className="tabular-nums">{r.reads}</span> },
];

interface Ver { id: string; platform: string; version: string; force: boolean; status: "RELEASED" | "GRAY" | "PENDING"; at: string; }
const VER_ROWS: Ver[] = [
  { id: "V1", platform: "Android", version: "2.8.1", force: false, status: "RELEASED", at: "07-10" },
  { id: "V2", platform: "iOS", version: "2.8.0", force: false, status: "RELEASED", at: "07-09" },
  { id: "V3", platform: "Android", version: "2.9.0-beta", force: false, status: "GRAY", at: "07-14" },
  { id: "V4", platform: "小程序", version: "1.5.2", force: true, status: "PENDING", at: "-" },
];
const VER_STATUS: Record<Ver["status"], [string, "success" | "warning" | "muted"]> = {
  RELEASED: ["已发布", "success"], GRAY: ["灰度", "warning"], PENDING: ["待发布", "muted"],
};
const VER_COLS: Column<Ver>[] = [
  { header: "平台", cell: (r) => <Badge tone="outline">{r.platform}</Badge> },
  { header: "版本号", cell: (r) => <span className="font-medium tabular-nums">{r.version}</span> },
  { header: "强制更新", cell: (r) => r.force ? <Badge tone="danger">强制</Badge> : <span className="text-muted-foreground">否</span> },
  { header: "状态", cell: (r) => <Badge tone={VER_STATUS[r.status][1]}>{VER_STATUS[r.status][0]}</Badge> },
  { header: "更新时间", cell: (r) => <span className="text-muted-foreground">{r.at}</span> },
];

interface Bat { id: string; site: string; inStock: number; rented: number; fault: number; health: number; }
const BAT_ROWS: Bat[] = [
  { id: "B1", site: "陆家嘴中心", inStock: 42, rented: 18, fault: 1, health: 98 },
  { id: "B2", site: "虹桥枢纽 T2", inStock: 65, rented: 40, fault: 3, health: 96 },
  { id: "B3", site: "南京路步行街", inStock: 28, rented: 22, fault: 0, health: 100 },
  { id: "B4", site: "北外滩来福士", inStock: 33, rented: 12, fault: 2, health: 95 },
];
const BAT_COLS: Column<Bat>[] = [
  { header: "站点", cell: (r) => <span className="font-medium">{r.site}</span> },
  { header: "在库", cell: (r) => <span className="tabular-nums">{r.inStock}</span> },
  { header: "借出", cell: (r) => <span className="tabular-nums">{r.rented}</span> },
  { header: "故障", cell: (r) => r.fault > 0 ? <Badge tone="danger">{r.fault}</Badge> : <span className="tabular-nums text-muted-foreground">0</span> },
  { header: "健康率", cell: (r) => <span className="tabular-nums">{r.health}%</span> },
];

interface Bank { id: string; name: string; bank: string; acct: string; currency: string; on: boolean; }
const BANK_ROWS: Bank[] = [
  { id: "K1", name: "简电（上海）科技有限公司", bank: "招商银行 张江支行", acct: "6214 **** **** 8821", currency: "CNY", on: true },
  { id: "K2", name: "简电香港有限公司", bank: "HSBC Hong Kong", acct: "004 **** **** 3390", currency: "HKD", on: true },
  { id: "K3", name: "结算备用账户", bank: "工商银行 世纪大道支行", acct: "6222 **** **** 1074", currency: "CNY", on: false },
];
const BANK_COLS: Column<Bank>[] = [
  { header: "账户名", cell: (r) => <span className="font-medium">{r.name}</span> },
  { header: "开户行", cell: (r) => r.bank },
  { header: "账号", cell: (r) => <span className="tabular-nums text-muted-foreground">{r.acct}</span> },
  { header: "币种", cell: (r) => <Badge tone="outline">{r.currency}</Badge> },
  { header: "状态", cell: (r) => r.on ? <Badge tone="success">启用</Badge> : <Badge tone="muted">停用</Badge> },
];

interface Fb { id: string; user: string; type: string; digest: string; status: "OPEN" | "REPLIED" | "CLOSED"; at: string; }
const FB_ROWS: Fb[] = [
  { id: "F1", user: "1358****201", type: "退款咨询", digest: "订单已归还但仍在计费", status: "REPLIED", at: "07-14 09:20" },
  { id: "F2", user: "1899****663", type: "设备故障", digest: "柜机弹不出充电宝", status: "OPEN", at: "07-14 08:55" },
  { id: "F3", user: "1370****118", type: "功能建议", digest: "希望支持多语言", status: "CLOSED", at: "07-13 20:10" },
  { id: "F4", user: "1521****904", type: "投诉", digest: "站点找不到可借设备", status: "OPEN", at: "07-13 18:32" },
];
const FB_STATUS: Record<Fb["status"], [string, "warning" | "success" | "muted"]> = {
  OPEN: ["待处理", "warning"], REPLIED: ["已回复", "success"], CLOSED: ["已关闭", "muted"],
};
const FB_COLS: Column<Fb>[] = [
  { header: "用户", cell: (r) => r.user },
  { header: "类型", cell: (r) => <Badge tone="outline">{r.type}</Badge> },
  { header: "内容摘要", cell: (r) => <span className="font-medium">{r.digest}</span> },
  { header: "状态", cell: (r) => <Badge tone={FB_STATUS[r.status][1]}>{FB_STATUS[r.status][0]}</Badge> },
  { header: "时间", cell: (r) => <span className="text-muted-foreground">{r.at}</span> },
];

interface Fx { id: string; pair: string; rate: number; source: string; at: string; }
const FX_ROWS: Fx[] = [
  { id: "X1", pair: "USD / CNY", rate: 7.1820, source: "中国银行", at: "07-14 08:00" },
  { id: "X2", pair: "HKD / CNY", rate: 0.9186, source: "中国银行", at: "07-14 08:00" },
  { id: "X3", pair: "EUR / CNY", rate: 7.8402, source: "ECB", at: "07-14 08:00" },
  { id: "X4", pair: "SGD / CNY", rate: 5.3305, source: "MAS", at: "07-14 08:00" },
];
const FX_COLS: Column<Fx>[] = [
  { header: "币种对", cell: (r) => <span className="font-medium tabular-nums">{r.pair}</span> },
  { header: "汇率", cell: (r) => <span className="tabular-nums">{r.rate.toFixed(4)}</span> },
  { header: "来源", cell: (r) => <Badge tone="outline">{r.source}</Badge> },
  { header: "更新时间", cell: (r) => <span className="text-muted-foreground">{r.at}</span> },
];

function Body({ tab }: { tab: string }) {
  switch (tab) {
    case "app-versions":
      return <MockTabView stats={[{ label: "在架版本", value: 3 }, { label: "灰度中", value: 1, tone: "down" }, { label: "最新 Android", value: "2.8.1" }, { label: "最新 iOS", value: "2.8.0" }]} columns={VER_COLS} rows={VER_ROWS} />;
    case "battery-stats":
      return <MockTabView stats={[{ label: "在库电池", value: 168 }, { label: "借出中", value: 92 }, { label: "故障", value: 6, tone: "down" }, { label: "平均健康率", value: "97%" }]} columns={BAT_COLS} rows={BAT_ROWS} />;
    case "bank-accounts":
      return <MockTabView stats={[{ label: "结算账户", value: 3 }, { label: "启用", value: 2 }, { label: "币种", value: 2 }, { label: "本月结算额", value: "¥1.82M" }]} columns={BANK_COLS} rows={BANK_ROWS} />;
    case "feedback":
      return <MockTabView stats={[{ label: "今日反馈", value: 24 }, { label: "待处理", value: 7, tone: "down" }, { label: "已回复", value: 15 }, { label: "平均响应", value: "18m" }]} columns={FB_COLS} rows={FB_ROWS} />;
    case "fx":
      return <MockTabView stats={[{ label: "启用币种", value: 5 }, { label: "汇率来源", value: 3 }, { label: "更新频率", value: "每日" }, { label: "最近更新", value: "08:00" }]} columns={FX_COLS} rows={FX_ROWS} />;
    default:
      return <MockTabView stats={[{ label: "本月公告", value: 12 }, { label: "已发布", value: 9 }, { label: "定时待发", value: 2 }, { label: "总阅读", value: "10.6K" }]} columns={ANN_COLS} rows={ANN_ROWS} />;
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

export default function OperationsPage() {
  return <Suspense fallback={null}><Inner /></Suspense>;
}
