"use client";

// 集成中心（integration 模块，仅 ADMIN）：Neargo 支付 / OpenAPI / POS / Kiosk / 地图 / KYC。
// Neargo P2，其余 P3（导航仅作标识，可正常点击）。演示态内容页（本地样本数据）。
import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { TabHeader } from "@/components/ui/tab-header";
import { MockTabView } from "@/components/ui/mock-view";
import { type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";

const TABS = [
  { key: "neargo", label: "Neargo 支付", phase: 2 as const },
  { key: "openapi", label: "OpenAPI 应用", phase: 3 as const },
  { key: "pos", label: "POS 对接", phase: 3 as const },
  { key: "kiosk", label: "Kiosk 自助机", phase: 3 as const },
  { key: "maps", label: "地图服务", phase: 3 as const },
  { key: "kyc", label: "第三方 KYC", phase: 3 as const },
];

const CONN: Record<string, ["已连接" | "异常" | "未启用", "success" | "danger" | "muted"]> = {
  OK: ["已连接", "success"], ERR: ["异常", "danger"], OFF: ["未启用", "muted"],
};

interface Chan { id: string; name: string; env: string; conn: keyof typeof CONN; sync: string; }
const CHAN_ROWS: Chan[] = [
  { id: "P1", name: "Neargo 钱包支付", env: "生产", conn: "OK", sync: "07-14 09:30" },
  { id: "P2", name: "Neargo 免密代扣", env: "生产", conn: "OK", sync: "07-14 09:30" },
  { id: "P3", name: "Neargo 退款通道", env: "生产", conn: "OK", sync: "07-14 09:28" },
  { id: "P4", name: "Neargo 结算回调", env: "沙箱", conn: "OFF", sync: "-" },
];
const CHAN_COLS: Column<Chan>[] = [
  { header: "通道", cell: (r) => <span className="font-medium">{r.name}</span> },
  { header: "环境", cell: (r) => <Badge tone="outline">{r.env}</Badge> },
  { header: "连接状态", cell: (r) => <Badge tone={CONN[r.conn][1]}>{CONN[r.conn][0]}</Badge> },
  { header: "最近同步", cell: (r) => <span className="text-muted-foreground">{r.sync}</span> },
];

interface App { id: string; name: string; scope: string; calls: string; conn: keyof typeof CONN; }
const APP_ROWS: App[] = [
  { id: "app_9f2c", name: "商户自助对账应用", scope: "finance:read", calls: "12.4K/日", conn: "OK" },
  { id: "app_7a1b", name: "第三方巡检小程序", scope: "device:read, workorder:*", calls: "3.1K/日", conn: "OK" },
  { id: "app_5d80", name: "数据大屏对接", scope: "report:read", calls: "860/日", conn: "ERR" },
];
const APP_COLS: Column<App>[] = [
  { header: "AppID", cell: (r) => <span className="font-medium tabular-nums">{r.id}</span> },
  { header: "应用名", cell: (r) => r.name },
  { header: "权限范围", cell: (r) => <Badge tone="outline">{r.scope}</Badge> },
  { header: "调用量", cell: (r) => <span className="tabular-nums">{r.calls}</span> },
  { header: "状态", cell: (r) => <Badge tone={CONN[r.conn][1]}>{CONN[r.conn][0]}</Badge> },
];

interface Pos { id: string; store: string; model: string; conn: keyof typeof CONN; hb: string; }
const POS_ROWS: Pos[] = [
  { id: "POS-01", store: "星巴克 陆家嘴", model: "Verifone P400", conn: "OK", hb: "1 分钟前" },
  { id: "POS-02", store: "万达影城 五角场", model: "Ingenico Move", conn: "OK", hb: "2 分钟前" },
  { id: "POS-03", store: "全家 世纪大道", model: "Sunmi T2", conn: "ERR", hb: "3 小时前" },
];
const POS_COLS: Column<Pos>[] = [
  { header: "POS 编号", cell: (r) => <span className="font-medium">{r.id}</span> },
  { header: "门店", cell: (r) => r.store },
  { header: "型号", cell: (r) => <Badge tone="outline">{r.model}</Badge> },
  { header: "对接状态", cell: (r) => <Badge tone={CONN[r.conn][1]}>{CONN[r.conn][0]}</Badge> },
  { header: "最近心跳", cell: (r) => <span className="text-muted-foreground">{r.hb}</span> },
];

interface Kiosk { id: string; loc: string; model: string; online: boolean; ver: string; }
const KIOSK_ROWS: Kiosk[] = [
  { id: "KSK-1001", loc: "虹桥枢纽 T2 出发层", model: "SH-Kiosk 21″", online: true, ver: "1.4.0" },
  { id: "KSK-1002", loc: "陆家嘴中心 B1", model: "SH-Kiosk 21″", online: true, ver: "1.4.0" },
  { id: "KSK-1003", loc: "南京路步行街", model: "SH-Kiosk 27″", online: false, ver: "1.3.6" },
];
const KIOSK_COLS: Column<Kiosk>[] = [
  { header: "自助机", cell: (r) => <span className="font-medium">{r.id}</span> },
  { header: "位置", cell: (r) => r.loc },
  { header: "型号", cell: (r) => <Badge tone="outline">{r.model}</Badge> },
  { header: "在线", cell: (r) => r.online ? <Badge tone="success">在线</Badge> : <Badge tone="muted">离线</Badge> },
  { header: "版本", cell: (r) => <span className="tabular-nums text-muted-foreground">{r.ver}</span> },
];

interface Maps { id: string; vendor: string; usage: string; quota: string; conn: keyof typeof CONN; }
const MAPS_ROWS: Maps[] = [
  { id: "MP1", vendor: "高德地图", usage: "站点定位 / 逆地理", quota: "68% / 100万", conn: "OK" },
  { id: "MP2", vendor: "Google Maps", usage: "海外站点", quota: "22% / 50万", conn: "OK" },
  { id: "MP3", vendor: "Mapbox", usage: "热力图渲染", quota: "未启用", conn: "OFF" },
];
const MAPS_COLS: Column<Maps>[] = [
  { header: "服务商", cell: (r) => <span className="font-medium">{r.vendor}</span> },
  { header: "用途", cell: (r) => r.usage },
  { header: "配额用量", cell: (r) => <span className="tabular-nums">{r.quota}</span> },
  { header: "状态", cell: (r) => <Badge tone={CONN[r.conn][1]}>{CONN[r.conn][0]}</Badge> },
];

interface Kyc { id: string; vendor: string; country: string; method: string; pass: string; conn: keyof typeof CONN; }
const KYC_ROWS: Kyc[] = [
  { id: "Y1", vendor: "旷视 FaceID", country: "🇨🇳 中国", method: "身份证 + 人脸", pass: "97.2%", conn: "OK" },
  { id: "Y2", vendor: "Onfido", country: "🇬🇧 全球", method: "证件 + 活体", pass: "94.8%", conn: "OK" },
  { id: "Y3", vendor: "Jumio", country: "🇸🇬 东南亚", method: "护照 OCR", pass: "92.1%", conn: "OFF" },
];
const KYC_COLS: Column<Kyc>[] = [
  { header: "服务商", cell: (r) => <span className="font-medium">{r.vendor}</span> },
  { header: "覆盖", cell: (r) => r.country },
  { header: "认证方式", cell: (r) => <Badge tone="outline">{r.method}</Badge> },
  { header: "通过率", cell: (r) => <span className="tabular-nums">{r.pass}</span> },
  { header: "状态", cell: (r) => <Badge tone={CONN[r.conn][1]}>{CONN[r.conn][0]}</Badge> },
];

function Body({ tab }: { tab: string }) {
  switch (tab) {
    case "openapi":
      return <MockTabView stats={[{ label: "接入应用", value: 8 }, { label: "在用", value: 6 }, { label: "今日调用", value: "16.4K" }, { label: "异常", value: 1, tone: "down" }]} columns={APP_COLS} rows={APP_ROWS} />;
    case "pos":
      return <MockTabView stats={[{ label: "POS 终端", value: 142 }, { label: "在线", value: 137 }, { label: "异常", value: 5, tone: "down" }, { label: "覆盖门店", value: 96 }]} columns={POS_COLS} rows={POS_ROWS} />;
    case "kiosk":
      return <MockTabView stats={[{ label: "自助机", value: 34 }, { label: "在线", value: 31 }, { label: "离线", value: 3, tone: "down" }, { label: "最新版本", value: "1.4.0" }]} columns={KIOSK_COLS} rows={KIOSK_ROWS} />;
    case "maps":
      return <MockTabView stats={[{ label: "地图服务商", value: 3 }, { label: "已启用", value: 2 }, { label: "月调用", value: "88万" }, { label: "配额峰值", value: "68%" }]} columns={MAPS_COLS} rows={MAPS_ROWS} />;
    case "kyc":
      return <MockTabView stats={[{ label: "KYC 服务商", value: 3 }, { label: "已启用", value: 2 }, { label: "今日认证", value: 1240 }, { label: "平均通过率", value: "95%" }]} columns={KYC_COLS} rows={KYC_ROWS} />;
    default:
      return <MockTabView stats={[{ label: "支付通道", value: 4 }, { label: "已连接", value: 3 }, { label: "今日交易", value: "38.2K" }, { label: "成功率", value: "99.6%" }]} columns={CHAN_COLS} rows={CHAN_ROWS} />;
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

export default function IntegrationsPage() {
  return <Suspense fallback={null}><Inner /></Suspense>;
}
