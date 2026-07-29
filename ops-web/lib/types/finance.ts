import type { AuditTrail } from "./common";
// 覆盖范围：财务域（trade）——分润规则/明细/统计、结算、提现、账务分录、
// 对账、发票、充值订单。

export interface ShareRule {
  ruleNo: string;
  dimension: "VENUE" | "AGENT";
  payeeName: string;
  mode: "CHANNEL_SPLIT" | "LEDGER";
  rate: number; // 0..1
  priority: number;
}
export interface Settlement {
  settleNo: string;
  payeeType: "VENUE" | "AGENT";
  payeeName: string;
  period: string;
  totalAmount: number;
  currency: string;
  status: "GEN" | "CONFIRMED" | "PAID";
}
export interface Withdrawal extends AuditTrail {
  withdrawNo: string;
  payeeName: string;
  amount: number;
  currency: string;
  status: "APPLY" | "AUDIT" | "PAYING" | "PAID" | "FAILED";
  appliedAt: string;
  // —— 资金审批合规四件套（对标补齐）——
  fee: number; // 提现手续费（与 amount 同币种，实际到账 = amount - fee）
}

// —— 账务分录（复式记账，trade 域）——
export interface LedgerEntry {
  entryNo: string;
  voucherNo: string;
  orderNo: string | null;
  account: string; // 账户：现金/应付场地方/应付代理/平台收入…
  direction: "DEBIT" | "CREDIT"; // 借/贷
  amount: number;
  currency: string;
  summary: string;
  createdAt: string;
}

// —— 财务 · 待建功能补全（trade 域）——
export interface ShareRecord {
  recordNo: string;
  orderNo: string;
  dimension: "VENUE" | "AGENT";
  payeeName: string;
  amount: number;
  rate: number; // 0..1
  currency: string;
  createdAt: string;
}
export interface Reconcile {
  batchNo: string;
  period: string;
  nearpayTotal: number;
  ledgerTotal: number;
  diff: number;
  currency: string;
  status: "MATCHED" | "DIFF";
  createdAt: string;
}
export interface Invoice {
  invoiceNo: string;
  payeeName: string;
  amount: number;
  vatTrn: string;
  currency: string;
  status: "DRAFT" | "ISSUED" | "VOID";
  issuedAt: string;
}

// —— 分润统计（财务域 · 阶段 2，对标简电云「佣金统计」）——
// 竞品把佣金统计按「运营商 / 商户」切成两套菜单两张表；我们做**一张表 + 顶部维度切换器**，
// 同一套列、同一次查询，少一次跳转——这是「信息更清晰」的正例。
export interface ShareSummary {
  /** 主体维度：与 ShareRule / ShareRecord 的 dimension 同枚举，切换器切的就是它 */
  dimension: "VENUE" | "AGENT";
  payeeNo: string; // 场地方 VEN3xx / 代理商 AG00x
  payeeName: string;
  period: string; // 统计周期 `2026-07`
  orderCount: number;
  gmv: number; // 交易额
  shareAmount: number; // 分润额
  settledAmount: number; // 已结算（≤ 分润额）
  pendingAmount: number; // 待结算 = 分润额 − 已结算（列表高亮列：财务最关心的数）
  currency: string;
}

// —— 充值订单（财务域 · 阶段 3）——
// 归「用户账」分组，与钱包同主体。channelCode 取值必须与 系统设置·支付渠道 的 channelCode 对得上。
export interface RechargeOrder {
  rechargeNo: string;
  userNo: string; // U30xx
  nickname: string; // 冗余展示用：列表不必再跳用户页确认是谁
  packageNo: string | null; // 充值套餐；null = 自定义金额
  payAmount: number; // 实付
  giftAmount: number; // 赠送
  creditAmount: number; // 到账 = 实付 + 赠送
  currency: string;
  channelCode: string; // NEARPAY / STRIPE / TAP …（关联 PaymentChannel.channelCode）
  status: "PENDING" | "PAID" | "FAILED" | "REFUNDED";
  createdAt: string; // 下单时间：PENDING/FAILED 无 paidAt，日期范围筛选一律以本字段为准
  paidAt: string | null;
  psgTxnNo: string | null; // 支付网关流水号；未支付为空
}
