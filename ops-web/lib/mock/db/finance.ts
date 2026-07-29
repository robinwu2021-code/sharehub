// 财务域：分润规则/明细/统计 · 结算单 · 提现（含审批）· 账务分录 · 对账 · 发票 · 充值订单/套餐。
// 跨域自洽：分成方一律引用 location.ts 的 venues 与 agent.ts 的 agents；
// 充值订单的用户引用 user.ts 的 cUsers，渠道码取自 system.ts 的 paymentChannels 口径。
import type {
  ShareRule, Settlement, Withdrawal, LedgerEntry, ShareRecord, Reconcile, Invoice,
  ShareSummary, RechargeOrder, RechargePackage, PageQuery,
} from "../../types";
import { VENUE_NAMES, p, iso } from "./internal";
import { paginate, kwHit, upsert, nextNo, liveHit, archiveRow, unarchiveRow } from "./helpers";
import { agents } from "./agent";
import { venues } from "./location";
import { cUsers } from "./user";

// —— 分润规则 / 结算 / 提现 ——
// 分成方名字必须是真实的场地方或代理商（原先写死 "Agent-North"/"Agent-South"，
// agents 里没有这两个名字，分润规则/结算单/提现单点进去都对不上代理商档案）。
const PAYEE_NAMES = [...VENUE_NAMES, ...agents.slice(0, 2).map((a) => a.name)];
export const shareRules: ShareRule[] = Array.from({ length: 12 }, (_, i) => ({
  ruleNo: `SR${600 + i}`, dimension: i % 3 === 0 ? "AGENT" : "VENUE", payeeName: p(PAYEE_NAMES, i),
  mode: i % 4 === 0 ? "CHANNEL_SPLIT" : "LEDGER", rate: [0.15, 0.2, 0.25][i % 3], priority: (i % 3) + 1,
}));
export const settlements: Settlement[] = Array.from({ length: 24 }, (_, i) => ({
  settleNo: `STL${700 + i}`, payeeType: i % 3 === 0 ? "AGENT" : "VENUE", payeeName: p(PAYEE_NAMES, i),
  period: `2026-${String((i % 6) + 1).padStart(2, "0")}`, totalAmount: 800 + (i * 137) % 4000, currency: "AED",
  status: p(["GEN", "CONFIRMED", "PAID"] as const, i),
}));
// 提现：APPLY/AUDIT = 未审批（审批四列为空）；PAYING/PAID = 已通过；FAILED = 已驳回（必带原因）
const WD_REJECT = ["银行账户与合同主体不一致", "本期结算单未确认，暂缓打款", "超出单笔提现限额，需拆单重申"];
export const withdrawals: Withdrawal[] = Array.from({ length: 20 }, (_, i) => {
  const status = p(["APPLY", "AUDIT", "PAYING", "PAID", "FAILED"] as const, i);
  const amount = 500 + (i * 211) % 3000;
  const audited = status === "PAYING" || status === "PAID" || status === "FAILED";
  return {
    withdrawNo: `WD${3000 + i}`, payeeName: p(PAYEE_NAMES, i), amount,
    // 手续费 = 金额 0.6%，下限 2 AED（与提现渠道成本口径一致）
    fee: Number(Math.max(2, amount * 0.006).toFixed(2)),
    currency: "AED", status, appliedAt: iso(i * 43200_000),
    auditorName: audited ? p(["Sara Ahmed", "Omar Khan", "admin"], i) : null,
    auditedAt: audited ? iso(i * 43200_000 - 7200_000) : null,
    rejectReason: status === "FAILED" ? p(WD_REJECT, i) : null,
  };
});

// —— 账务分录（复式：每笔业务借贷成对）——
const ACCTS = ["现金-nearpay", "平台收入", "应付场地方", "应付代理", "押金负债"];
export const ledger: LedgerEntry[] = Array.from({ length: 60 }, (_, i) => {
  const pair = Math.floor(i / 2);
  const debit = i % 2 === 0;
  const amt = 3 + (pair * 7) % 25;
  return {
    entryNo: `LE${9000 + i}`, voucherNo: `V${2000 + pair}`, orderNo: `ORD${500000 + pair}`,
    account: debit ? "现金-nearpay" : p(ACCTS.slice(1), pair), direction: debit ? "DEBIT" : "CREDIT",
    amount: amt, currency: "AED", summary: debit ? "收款入账" : p(["平台分成", "场地方分润", "代理分润", "押金冻结"], pair),
    createdAt: iso(i * 1800_000),
  };
});

// —— 分润明细 / 对账 / 发票 ——
export const shareRecords: ShareRecord[] = Array.from({ length: 24 }, (_, i) => {
  const dim = i % 3 === 0 ? "AGENT" : "VENUE";
  return {
    recordNo: `SREC${9000 + i}`, orderNo: `ORD${500000 + i}`, dimension: dim,
    payeeName: dim === "AGENT" ? p(agents, i).name : p(VENUE_NAMES, i),
    amount: Number((1 + (i * 7) % 20 + (i % 10) / 10).toFixed(2)),
    rate: p([0.15, 0.2, 0.25, 0.3], i), currency: "AED", createdAt: iso(i * 3600_000),
  };
});
export const reconciles: Reconcile[] = Array.from({ length: 12 }, (_, i) => {
  const nearpay = 30000 + (i * 3137) % 50000;
  const diff = i % 4 === 0 ? (i % 2 === 0 ? 1 : -1) * (12 + (i * 3) % 80) : 0;
  return {
    batchNo: `RC${2026000 + i}`, period: `2026-${String((i % 12) + 1).padStart(2, "0")}`,
    nearpayTotal: nearpay, ledgerTotal: nearpay - diff, diff, currency: "AED",
    status: diff === 0 ? "MATCHED" : "DIFF", createdAt: iso(i * 86400_000),
  };
});
export const invoices: Invoice[] = Array.from({ length: 18 }, (_, i) => ({
  invoiceNo: `INV${2026000 + i}`, payeeName: p(PAYEE_NAMES, i),
  amount: Number((500 + (i * 337) % 8000).toFixed(2)), vatTrn: `100${String(1000000000000 + i * 137).slice(0, 12)}`,
  currency: "AED", status: p(["DRAFT", "ISSUED", "ISSUED", "VOID"] as const, i), issuedAt: iso(i * 172800_000),
}));

export const listShareRecords = (q: PageQuery = {}) => paginate(shareRecords, q.page, q.size, (x) => kwHit(q.keyword, x.recordNo, x.orderNo, x.payeeName));
export const listReconciles = (q: PageQuery = {}) => paginate(reconciles, q.page, q.size, (x) => kwHit(q.keyword, x.batchNo, x.period));
export const listInvoices = (q: PageQuery = {}) => paginate(invoices, q.page, q.size, (x) => kwHit(q.keyword, x.invoiceNo, x.payeeName, x.vatTrn));

export const saveShareRule = (x: Partial<ShareRule>) => upsert(shareRules, x, "ruleNo", () => nextNo("SR", shareRules));
export const saveInvoice = (x: Partial<Invoice>) => upsert(invoices, x, "invoiceNo", () => nextNo("INV", invoices));

/** 提现审批（mock）：通过→PAYING，驳回→FAILED 并记原因；两者都落审批人/审批时间。 */
export function auditWithdrawal(withdrawNo: string, approve: boolean, rejectReason?: string, auditorName?: string): Withdrawal {
  const w = withdrawals.find((x) => x.withdrawNo === withdrawNo)!;
  w.auditorName = auditorName || "admin";
  w.auditedAt = new Date().toISOString();
  if (approve) {
    w.status = "PAYING";
    w.rejectReason = null;
  } else {
    w.status = "FAILED";
    w.rejectReason = rejectReason ?? "";
  }
  return w;
}

// —— §5 分润统计 ——
// 分成方一律引用现有 mock 实体：场地方取 venues（VEN3xx），代理商取 agents（AG00x），
// 保证与「分润规则 / 分润明细 / 结算单」跨页口径自洽（规格 §17.1-8）。
const SUMMARY_PERIODS = ["2026-07", "2026-06", "2026-05"];
const SUMMARY_PAYEES: { dimension: ShareSummary["dimension"]; payeeNo: string; payeeName: string }[] = [
  ...venues.map((v) => ({ dimension: "VENUE" as const, payeeNo: v.venueNo, payeeName: v.name })),
  ...agents.slice(0, 6).map((a) => ({ dimension: "AGENT" as const, payeeNo: a.agentNo, payeeName: a.name })),
];
export const shareSummaries: ShareSummary[] = SUMMARY_PERIODS.flatMap((period, pi) =>
  SUMMARY_PAYEES.map((payee, i) => {
    const orderCount = 320 + ((i * 137 + pi * 71) % 880);
    // 客单价 3.2~4.0 AED（与计费模板 PP001 的 30 分钟 3 AED 量级一致）
    const gmv = Number((orderCount * (3.2 + ((i * 3 + pi) % 9) / 10)).toFixed(2));
    // 分成比例沿用各自域的口径：场地方 15~25%，代理商 30~40%
    const rate = payee.dimension === "VENUE" ? [0.15, 0.2, 0.25][i % 3] : [0.3, 0.35, 0.4][i % 3];
    const shareAmount = Number((gmv * rate).toFixed(2));
    // 越早的周期结算越彻底：当月部分结算、上月大部分结清、上上月全清（settled ≤ share 恒成立）
    const settledRatio = pi === 0 ? [0, 0.4, 0.65][i % 3] : pi === 1 ? [0.8, 1, 0.9][i % 3] : 1;
    const settledAmount = Number((shareAmount * settledRatio).toFixed(2));
    return {
      ...payee, period, orderCount, gmv, shareAmount, settledAmount,
      pendingAmount: Number((shareAmount - settledAmount).toFixed(2)),
      currency: "AED",
    };
  }),
);

export type ShareSummaryQuery = PageQuery & {
  dimension?: string; // VENUE / AGENT（维度切换器）
  period?: string;
  sortKey?: string; // 目前仅 shareAmount / pendingAmount / gmv / orderCount
  sortDir?: string; // asc / desc
};
export const listShareSummaries = (q: ShareSummaryQuery = {}) => {
  const rows = shareSummaries.filter((x) =>
    kwHit(q.keyword, x.payeeNo, x.payeeName) &&
    (!q.dimension || x.dimension === q.dimension) &&
    (!q.period || x.period === q.period));
  if (q.sortKey) {
    const dir = q.sortDir === "desc" ? -1 : 1;
    const key = q.sortKey as keyof ShareSummary;
    rows.sort((a, b) => (Number(a[key]) - Number(b[key])) * dir);
  }
  return paginate(rows, q.page, q.size);
};

// —— §8 充值套餐：比竞品多「赠额有效期」与「适用市场」 ——
// ⚠️ 必须声明在 rechargeOrders 之前：充值订单的套餐直接引用本数组（台账 M6）。
export const rechargePackages: RechargePackage[] = [
  { packageNo: "RP900", name: "体验包", payAmount: 20, giftAmount: 0, currency: "AED", markets: "AE", validDays: 90, sortNo: 1, status: "ENABLED", archivedAt: null },
  { packageNo: "RP901", name: "常用包", payAmount: 50, giftAmount: 5, currency: "AED", markets: "AE,SA", validDays: 180, sortNo: 2, status: "ENABLED", archivedAt: null },
  { packageNo: "RP902", name: "超值包", payAmount: 100, giftAmount: 15, currency: "AED", markets: "AE,SA,KW", validDays: 365, sortNo: 3, status: "ENABLED", archivedAt: null },
  { packageNo: "RP903", name: "家庭包", payAmount: 200, giftAmount: 40, currency: "AED", markets: "AE", validDays: 365, sortNo: 4, status: "ENABLED", archivedAt: null },
  { packageNo: "RP904", name: "斋月特惠包", payAmount: 80, giftAmount: 20, currency: "AED", markets: "AE,SA,QA", validDays: 60, sortNo: 5, status: "DISABLED", archivedAt: null },
  { packageNo: "RP905", name: "商户自用包", payAmount: 500, giftAmount: 60, currency: "AED", markets: "AE", validDays: 365, sortNo: 6, status: "DISABLED", archivedAt: "2026-06-15T08:00:00Z" },
];
export const listRechargePackages = (q: PageQuery & { status?: string } = {}) =>
  paginate(rechargePackages, q.page, q.size, (x) => {
    if (!liveHit(x, q.showArchived)) return false;
    if (!kwHit(q.keyword, x.packageNo, x.name, x.markets)) return false;
    if (q.status && x.status !== q.status) return false;
    return true;
  });
export const saveRechargePackage = (x: Partial<RechargePackage>) =>
  upsert(rechargePackages, x, "packageNo", () => nextNo("RP", rechargePackages));

// —— §6 充值订单 ——
// 用户引用 cUsers（U30xx）；channelCode 取自 paymentChannels 的真实渠道码（NEARPAY 为当前主通道）。
// 套餐一律取自上面的 rechargePackages（台账 M6：原先另有一份私有 RP001–004，
// 导致充值订单里的套餐号在套餐管理页查无此套餐）；只取在售套餐，另加一档自定义金额。
const RECHARGE_PACKAGE_POOL: { packageNo: string | null; pay: number; gift: number }[] = [
  ...rechargePackages
    .filter((x) => x.status === "ENABLED")
    .map((x) => ({ packageNo: x.packageNo, pay: x.payAmount, gift: x.giftAmount })),
  { packageNo: null, pay: 35, gift: 0 }, // 自定义金额：无套餐、无赠送
];
const RECHARGE_CHANNELS = ["NEARPAY", "NEARPAY", "NEARPAY", "STRIPE", "TAP", "NEARPAY", "CHECKOUT"];
export const rechargeOrders: RechargeOrder[] = Array.from({ length: 36 }, (_, i) => {
  const user = cUsers[i % cUsers.length];
  const pkg = p(RECHARGE_PACKAGE_POOL, i);
  const status = p(["PAID", "PAID", "PAID", "PENDING", "PAID", "FAILED", "PAID", "REFUNDED"] as const, i);
  const settled = status === "PAID" || status === "REFUNDED";
  return {
    rechargeNo: `RCG${70000 + i}`, userNo: user.cUserNo, nickname: user.nickname,
    packageNo: pkg.packageNo, payAmount: pkg.pay, giftAmount: pkg.gift,
    creditAmount: pkg.pay + pkg.gift, currency: "AED",
    channelCode: p(RECHARGE_CHANNELS, i), status,
    createdAt: iso(i * 21600_000),
    paidAt: settled ? iso(i * 21600_000 - 90_000) : null,
    psgTxnNo: settled ? `PSG${20260700 + i}` : null,
  };
});

export type RechargeQuery = PageQuery & { status?: string; from?: string; to?: string };
export const listRechargeOrders = (q: RechargeQuery = {}) =>
  paginate(rechargeOrders, q.page, q.size, (x) =>
    kwHit(q.keyword, x.rechargeNo, x.userNo, x.nickname, x.psgTxnNo, x.channelCode) &&
    (!q.status || x.status === q.status) &&
    // 日期范围按下单时间（PENDING/FAILED 没有 paidAt，用 paidAt 会把它们全筛掉）
    (!q.from || x.createdAt.slice(0, 10) >= q.from) &&
    (!q.to || x.createdAt.slice(0, 10) <= q.to));


// —— G1 软删除：充值套餐 ——
export const archiveRechargePackage = (no: string) => archiveRow(rechargePackages, "packageNo", no);
export const unarchiveRechargePackage = (no: string) => unarchiveRow(rechargePackages, "packageNo", no);
