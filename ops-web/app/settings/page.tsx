"use client";

// 平台设置（setting 模块，仅 ADMIN）：应用与品牌 / 支付设置 / 服务设置 / 其他设置。
// 对齐参考功能清单「平台设置」模块（应用管理 + 支付网关 + 服务集成 + 其他配置）。
// 均为 Phase 2（导航仅作标识，可正常点击）。演示态内容页（本地样本数据）。
import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { TabHeader } from "@/components/ui/tab-header";
import { MockTabView } from "@/components/ui/mock-view";
import { type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";

const TABS = [
  { key: "app", label: "应用与品牌", phase: 2 as const },
  { key: "payment", label: "支付设置", phase: 2 as const },
  { key: "service", label: "服务设置", phase: 2 as const },
  { key: "other", label: "其他设置", phase: 2 as const },
];

const OK = <Badge tone="success">已配置</Badge>;
const ON = <Badge tone="success">已启用</Badge>;
const OFF = <Badge tone="muted">未启用</Badge>;

// —— 应用与品牌（平台信息 / 应用信息 / 运营信息）——
interface Brand { id: string; item: string; scope: string; value: string; state: "OK" | "OFF"; }
const BRAND_ROWS: Brand[] = [
  { id: "BR1", item: "平台 LOGO", scope: "后台 / 大屏标识 · 120×120", value: "已上传", state: "OK" },
  { id: "BR2", item: "登录背景图", scope: "运营端登录页 · 2100×1000", value: "已上传", state: "OK" },
  { id: "BR3", item: "平台名称（多语言）", scope: "中 / 英 / 繁", value: "ShareHub 运营中心", state: "OK" },
  { id: "BR4", item: "大屏名称", scope: "数据大屏标题", value: "ShareHub 实时看板", state: "OK" },
  { id: "BR5", item: "应用 LOGO", scope: "C 端 App 图标", value: "已上传", state: "OK" },
  { id: "BR6", item: "应用名称（多语言）", scope: "C 端 App 名称", value: "简电充电宝", state: "OK" },
  { id: "BR7", item: "客服热线", scope: "App 内 / 工单展示", value: "400-820-6688", state: "OK" },
  { id: "BR8", item: "版权信息", scope: "页脚版权", value: "© 2026 简电科技", state: "OK" },
];
const BRAND_COLS: Column<Brand>[] = [
  { header: "配置项", cell: (r) => <span className="font-medium">{r.item}</span> },
  { header: "适用范围", cell: (r) => <span className="text-muted-foreground">{r.scope}</span> },
  { header: "当前值", cell: (r) => r.value },
  { header: "状态", cell: (r) => (r.state === "OK" ? ON : OFF) },
];

// —— 支付设置（支付方式 + 各网关配置）——
interface Pay { id: string; name: string; kind: string; currency: string; env: string; on: boolean; }
const PAY_ROWS: Pay[] = [
  { id: "PM1", name: "Neargo 钱包", kind: "预付费 + 免密代扣", currency: "CNY / HKD", env: "生产", on: true },
  { id: "PM2", name: "Stripe", kind: "预交易", currency: "全球卡", env: "生产", on: true },
  { id: "PM3", name: "PayPal", kind: "预交易", currency: "全球", env: "生产", on: true },
  { id: "PM4", name: "Braintree", kind: "预交易", currency: "全球卡", env: "沙箱", on: false },
  { id: "PM5", name: "Yedpay", kind: "预交易", currency: "HKD", env: "生产", on: true },
  { id: "PM6", name: "ABA（KHQR）", kind: "扫码 / 卡", currency: "KHR / USD", env: "生产", on: true },
  { id: "PM7", name: "Selcom", kind: "预付费", currency: "TZS", env: "沙箱", on: false },
];
const PAY_COLS: Column<Pay>[] = [
  { header: "支付通道", cell: (r) => <span className="font-medium">{r.name}</span> },
  { header: "类型", cell: (r) => <Badge tone="outline">{r.kind}</Badge> },
  { header: "适用币种", cell: (r) => <span className="text-muted-foreground">{r.currency}</span> },
  { header: "环境", cell: (r) => r.env },
  { header: "状态", cell: (r) => (r.on ? ON : OFF) },
];

// —— 服务设置（短信 / 邮件 / 个推 / 登录 / 第三方登录）——
interface Svc { id: string; name: string; usage: string; provider: string; state: "OK" | "OFF"; }
const SVC_ROWS: Svc[] = [
  { id: "SV1", name: "短信服务", usage: "验证码 / 通知", provider: "阿里云短信", state: "OK" },
  { id: "SV2", name: "邮件服务", usage: "SMTP 通知 / 发票", provider: "自建 SMTP", state: "OK" },
  { id: "SV3", name: "个推推送", usage: "App Push", provider: "GeTui", state: "OK" },
  { id: "SV4", name: "登录设置", usage: "邮箱 / 手机登录", provider: "手机 + 邮箱", state: "OK" },
  { id: "SV5", name: "第三方登录", usage: "社交账号登录", provider: "Google + Apple", state: "OK" },
];
const SVC_COLS: Column<Svc>[] = [
  { header: "服务", cell: (r) => <span className="font-medium">{r.name}</span> },
  { header: "用途", cell: (r) => <span className="text-muted-foreground">{r.usage}</span> },
  { header: "提供商", cell: (r) => <Badge tone="outline">{r.provider}</Badge> },
  { header: "状态", cell: (r) => (r.state === "OK" ? OK : OFF) },
];

// —— 其他设置（提现 / 预约 / 借还 / 发票 / 充值协议 / 密钥）——
interface Other { id: string; item: string; scope: string; value: string; on: boolean; }
const OTHER_ROWS: Other[] = [
  { id: "OT1", item: "提现设置", scope: "手续费 / 限额", value: "手续费 0.6% · 单日 ¥50,000", on: true },
  { id: "OT2", item: "预约设置", scope: "预约借还 / 爽约", value: "每日 3 次 · 爽约上限 5", on: true },
  { id: "OT3", item: "借还设置", scope: "余额门槛 / 自动结算", value: "起借余额 ¥10 · 封顶 ¥99", on: true },
  { id: "OT4", item: "发票设置", scope: "电子发票 / 税率", value: "增值税 6% · 抬头可配", on: true },
  { id: "OT5", item: "充值协议", scope: "充值前置协议", value: "富文本已配置", on: true },
  { id: "OT6", item: "密钥设置", scope: "地图 / 公钥", value: "高德 Key + RSA 公钥", on: true },
];
const OTHER_COLS: Column<Other>[] = [
  { header: "配置项", cell: (r) => <span className="font-medium">{r.item}</span> },
  { header: "说明", cell: (r) => <span className="text-muted-foreground">{r.scope}</span> },
  { header: "当前值", cell: (r) => r.value },
  { header: "状态", cell: (r) => (r.on ? ON : OFF) },
];

function Body({ tab }: { tab: string }) {
  switch (tab) {
    case "payment":
      return <MockTabView stats={[{ label: "支付通道", value: 7 }, { label: "已启用", value: 5 }, { label: "覆盖币种", value: 6 }, { label: "默认通道", value: "Neargo" }]} columns={PAY_COLS} rows={PAY_ROWS} />;
    case "service":
      return <MockTabView stats={[{ label: "服务配置", value: 5 }, { label: "已配置", value: 5 }, { label: "通知渠道", value: 3 }, { label: "登录方式", value: 4 }]} columns={SVC_COLS} rows={SVC_ROWS} />;
    case "other":
      return <MockTabView stats={[{ label: "其他配置", value: 6 }, { label: "已启用", value: 6 }, { label: "提现手续费", value: "0.6%" }, { label: "增值税率", value: "6%" }]} columns={OTHER_COLS} rows={OTHER_ROWS} />;
    default:
      return <MockTabView stats={[{ label: "品牌配置项", value: 8 }, { label: "多语言", value: 3 }, { label: "已配置", value: 8 }, { label: "待补充", value: 0 }]} columns={BRAND_COLS} rows={BRAND_ROWS} />;
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

export default function SettingsPage() {
  return <Suspense fallback={null}><Inner /></Suspense>;
}
