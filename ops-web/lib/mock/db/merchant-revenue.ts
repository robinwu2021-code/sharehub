// 商家营收首页（商家一期 B5）—— mock 口径与后端 MerchantRevenueController 逐条对应：
// 交易额 / 订单数按**已结算订单**、按结束时间落窗口；税前与我的分成按**分成明细**；
// 钱的状态（待结算 / 已结算未付 / 已付）按现在，不随窗口变。
// 商家会话只看自己的：订单经机柜的 agentNo 归属，分成与结算经 payeeNo。
import type { DeviceAlerts, RevenueByDevice, RevenueDay, RevenueSummary } from "../../types";
import { fail } from "../../biz-error";
import { cabinets } from "./device";
import { orders } from "./order";
import { settlements, shareRecords } from "./finance";

/** 与后端 `finance.share.tax_country=AE` 的 5% VAT 同值：税前 = 含税 / 1.05。 */
const VAT = 0.05;
const MAX_DAYS = 366;
const DAY = 86_400_000;

const ymd = (d: Date) => d.toISOString().slice(0, 10);
const round = (n: number) => Number(n.toFixed(2));

/** 缺省本月 1 日到今天；止早于起、超过 366 天 → 400（与后端 bad_window 同口径）。 */
function window(q: { from?: string; to?: string }): [string, string] {
  const today = new Date();
  const from = q.from ?? `${ymd(today).slice(0, 8)}01`;
  const to = q.to ?? ymd(today);
  const days = (Date.parse(to) - Date.parse(from)) / DAY + 1;
  if (!(days >= 1)) fail("窗口止日早于起日", "Window end is before start");
  if (days > MAX_DAYS) fail(`窗口最长 ${MAX_DAYS} 天`, `Window is at most ${MAX_DAYS} days`);
  return [from, to];
}

/** 上一个等长窗口。 */
function previous([from, to]: [string, string]): [string, string] {
  const len = Date.parse(to) - Date.parse(from) + DAY;
  return [ymd(new Date(Date.parse(from) - len)), ymd(new Date(Date.parse(from) - DAY))];
}

const inWindow = (at: string | null | undefined, [from, to]: [string, string]) =>
  !!at && at.slice(0, 10) >= from && at.slice(0, 10) <= to;

function settledOrders(agentNo: string | null, w: [string, string]) {
  const owner = new Map(cabinets.map((c) => [c.cabinetNo, c.agentNo]));
  return orders.filter((o) => o.status === "SETTLED" && inWindow(o.rentEndAt, w)
    && (!agentNo || owner.get(o.cabinetNo) === agentNo));
}

const myShares = (agentNo: string | null) =>
  shareRecords.filter((r) => r.dimension === "AGENT" && (!agentNo || r.payeeNo === agentNo));

export function revenueSummary(agentNo: string | null, q: { from?: string; to?: string }): RevenueSummary {
  const w = window(q);
  const cur = settledOrders(agentNo, w);
  const prev = settledOrders(agentNo, previous(w));
  const gross = round(cur.reduce((s, o) => s + o.feeAmount, 0));
  const shares = myShares(agentNo);
  const inCur = shares.filter((r) => inWindow(r.createdAt, w));
  const stl = settlements.filter((s) => s.payeeType === "AGENT" && (!agentNo || s.payeeNo === agentNo));
  return {
    from: w[0], to: w[1],
    orderCount: cur.length, gross, refund: 0, net: gross,
    preTax: round(inCur.reduce((s, r) => s + r.grossAmount / (1 + VAT), 0)),
    myShare: round(inCur.reduce((s, r) => s + r.amount, 0)),
    pending: round(shares.filter((r) => r.status === "PENDING").reduce((s, r) => s + r.amount, 0)),
    settledUnpaid: round(stl.filter((s) => s.status === "CONFIRMED").reduce((a, s) => a + s.totalAmount, 0)),
    paid: round(stl.filter((s) => s.status === "PAID").reduce((a, s) => a + s.totalAmount, 0)),
    prevOrderCount: prev.length,
    prevGross: round(prev.reduce((s, o) => s + o.feeAmount, 0)),
    prevMyShare: round(shares.filter((r) => inWindow(r.createdAt, previous(w))).reduce((s, r) => s + r.amount, 0)),
  };
}

export function revenueTrend(agentNo: string | null, q: { from?: string; to?: string }): RevenueDay[] {
  const w = window(q);
  const byDay = new Map<string, RevenueDay>();
  for (let t = Date.parse(w[0]); t <= Date.parse(w[1]); t += DAY) {
    const date = ymd(new Date(t));
    byDay.set(date, { date, orderCount: 0, gross: 0 });
  }
  for (const o of settledOrders(agentNo, w)) {
    const d = byDay.get(o.rentEndAt!.slice(0, 10));
    if (d) { d.orderCount += 1; d.gross = round(d.gross + o.feeAmount); }
  }
  return [...byDay.values()];
}

export function revenueByDevice(agentNo: string | null, q: { from?: string; to?: string; limit?: number }): RevenueByDevice[] {
  const acc = new Map<string, RevenueByDevice>();
  for (const o of settledOrders(agentNo, window(q))) {
    const d = acc.get(o.cabinetNo) ?? { cabinetNo: o.cabinetNo, orderCount: 0, gross: 0 };
    d.orderCount += 1; d.gross = round(d.gross + o.feeAmount);
    acc.set(o.cabinetNo, d);
  }
  return [...acc.values()].sort((a, b) => b.gross - a.gross).slice(0, q.limit ?? 10);
}

/** 设备提醒：与后端 DeviceAlertQuery 同口径（只数在用；离线与在线无可借互斥；样本 20）。 */
export function revenueAlerts(agentNo: string | null): DeviceAlerts {
  const mine = cabinets.filter((c) => c.status === "DEPLOYED" && (!agentNo || c.agentNo === agentNo));
  const offline = mine.filter((c) => c.onlineStatus === "OFFLINE");
  const empty = mine.filter((c) => c.onlineStatus === "ONLINE" && !(c.availableCount > 0));
  const no = (xs: typeof mine) => xs.map((c) => c.cabinetNo).sort().slice(0, 20);
  return { deployed: mine.length, offline: offline.length, noBorrowable: empty.length,
    offlineSample: no(offline), noBorrowableSample: no(empty) };
}
