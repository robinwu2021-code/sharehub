"use client";

// 充值管理（topup 模块）：充值套餐 / 充值订单。演示态内容页（本地样本数据）。
import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { TabHeader } from "@/components/ui/tab-header";
import { MockTabView, type MockStat } from "@/components/ui/mock-view";
import { type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";

const TABS = [
  { key: "packages", label: "充值套餐", phase: 2 as const },
  { key: "orders", label: "充值订单", phase: 2 as const },
];

interface Pkg { id: string; name: string; face: number; bonus: number; price: number; sold: number; on: boolean; }
interface TopupOrder { id: string; user: string; pkg: string; amount: number; pay: string; status: "PAID" | "REFUND" | "PENDING"; at: string; }

const PKG_ROWS: Pkg[] = [
  { id: "PKG-01", name: "体验包", face: 10, bonus: 0, price: 10, sold: 1284, on: true },
  { id: "PKG-02", name: "标准包", face: 30, bonus: 3, price: 30, sold: 968, on: true },
  { id: "PKG-03", name: "超值包", face: 50, bonus: 8, price: 50, sold: 642, on: true },
  { id: "PKG-04", name: "尊享包", face: 100, bonus: 20, price: 100, sold: 213, on: true },
  { id: "PKG-05", name: "旧版促销包", face: 20, bonus: 5, price: 20, sold: 57, on: false },
];
const PKG_COLS: Column<Pkg>[] = [
  { header: "套餐", cell: (r) => <span className="font-medium">{r.name}</span> },
  { header: "编号", cell: (r) => <span className="text-muted-foreground">{r.id}</span> },
  { header: "面值", cell: (r) => <span className="tabular-nums">¥{r.face}</span> },
  { header: "赠送", cell: (r) => <span className="tabular-nums">¥{r.bonus}</span> },
  { header: "售价", cell: (r) => <span className="tabular-nums">¥{r.price}</span> },
  { header: "累计售出", cell: (r) => <span className="tabular-nums">{r.sold}</span> },
  { header: "状态", cell: (r) => r.on ? <Badge tone="success">上架</Badge> : <Badge tone="muted">下架</Badge> },
];

const PAY_LABEL: Record<string, string> = { NEARGO: "Neargo", ALIPAY: "支付宝", WECHAT: "微信", CARD: "银行卡" };
const ORDER_ROWS: TopupOrder[] = [
  { id: "TU-20260714-0912", user: "1358****201", pkg: "超值包", amount: 50, pay: "NEARGO", status: "PAID", at: "07-14 09:12" },
  { id: "TU-20260714-0847", user: "1899****663", pkg: "标准包", amount: 30, pay: "ALIPAY", status: "PAID", at: "07-14 08:47" },
  { id: "TU-20260714-0805", user: "1370****118", pkg: "尊享包", amount: 100, pay: "WECHAT", status: "PENDING", at: "07-14 08:05" },
  { id: "TU-20260713-2231", user: "1521****904", pkg: "体验包", amount: 10, pay: "NEARGO", status: "PAID", at: "07-13 22:31" },
  { id: "TU-20260713-1958", user: "1666****027", pkg: "超值包", amount: 50, pay: "CARD", status: "REFUND", at: "07-13 19:58" },
];
const ORDER_STATUS: Record<TopupOrder["status"], [string, "success" | "warning" | "muted"]> = {
  PAID: ["已到账", "success"], PENDING: ["处理中", "warning"], REFUND: ["已退款", "muted"],
};
const ORDER_COLS: Column<TopupOrder>[] = [
  { header: "充值单号", cell: (r) => <span className="font-medium">{r.id}</span> },
  { header: "用户", cell: (r) => r.user },
  { header: "套餐", cell: (r) => r.pkg },
  { header: "金额", cell: (r) => <span className="tabular-nums">¥{r.amount}</span> },
  { header: "支付方式", cell: (r) => <Badge tone="outline">{PAY_LABEL[r.pay]}</Badge> },
  { header: "状态", cell: (r) => <Badge tone={ORDER_STATUS[r.status][1]}>{ORDER_STATUS[r.status][0]}</Badge> },
  { header: "时间", cell: (r) => <span className="text-muted-foreground">{r.at}</span> },
];

const PKG_STATS: MockStat[] = [
  { label: "在架套餐", value: 4 },
  { label: "今日充值笔数", value: 312 },
  { label: "今日充值额", value: "¥18,640", sub: "+12.4%" },
  { label: "平均客单", value: "¥59.7" },
];
const ORDER_STATS: MockStat[] = [
  { label: "今日订单", value: 312 },
  { label: "已到账", value: 298 },
  { label: "处理中", value: 9, tone: "down" },
  { label: "退款", value: 5 },
];

function Inner() {
  const sp = useSearchParams();
  const qTab = sp.get("tab");
  const [tab, setTab] = useState(TABS.some((t) => t.key === qTab) ? (qTab as string) : TABS[0].key);
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) setTab(qTab); }, [qTab]);
  return (
    <div>
      <TabHeader tabs={TABS} value={tab} onChange={setTab} />
      {tab === "packages"
        ? <MockTabView stats={PKG_STATS} columns={PKG_COLS} rows={PKG_ROWS} />
        : <MockTabView stats={ORDER_STATS} columns={ORDER_COLS} rows={ORDER_ROWS} />}
    </div>
  );
}

export default function TopupPage() {
  return <Suspense fallback={null}><Inner /></Suspense>;
}
