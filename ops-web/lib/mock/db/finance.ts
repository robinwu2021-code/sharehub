// 财务域：分润规则/明细/统计 · 结算单 · 提现（含审批）· 账务分录 · 对账 · 发票 · 充值订单/套餐。
// 跨域自洽：分成方一律引用 location.ts 的 venues 与 agent.ts 的 agents；
// 充值订单的用户引用 user.ts 的 cUsers，渠道码取自 system.ts 的 paymentChannels 口径。
import type {
  ShareRule, Settlement, SettlementStatus, SettlementAction, SettlementDraft,
  Withdrawal, LedgerEntry, ShareRecord, Reconcile, Invoice,
  ShareSummary, RechargeOrder, RechargePackage, PageQuery,
  ReconHandleStatus, ReconHandleResult, ReconAction, ReconStats,
  InvoiceStatus, InvoiceAction,
} from "../../types";
import {
  STL_TRANSITIONS, canSettlementTransition,
  RECON_TRANSITIONS, canReconTransition, RECON_TERMINAL,
  INV_TRANSITIONS, canInvoiceTransition, canEditInvoiceFields,
} from "../../types";
import { VENUE_NAMES, p, iso } from "./internal";
import { paginate, kwHit, upsert, nextNo, liveHit, archiveRow, unarchiveRow } from "./helpers";
import { agents } from "./agent";
import { venues } from "./location";
import { orders } from "./order";
import { cUsers } from "./user";

// —— 分润规则 / 结算 / 提现 ——
// 分成方名字必须是真实的场地方或代理商（原先写死 "Agent-North"/"Agent-South"，
// agents 里没有这两个名字，分润规则/结算单/提现单点进去都对不上代理商档案）。
const PAYEE_NAMES = [...VENUE_NAMES, ...agents.slice(0, 2).map((a) => a.name)];
export const shareRules: ShareRule[] = Array.from({ length: 12 }, (_, i) => ({
  ruleNo: `SR${600 + i}`, dimension: i % 3 === 0 ? "AGENT" : "VENUE", payeeName: p(PAYEE_NAMES, i),
  mode: i % 4 === 0 ? "CHANNEL_SPLIT" : "LEDGER", rate: [0.15, 0.2, 0.25][i % 3], priority: (i % 3) + 1,
}));
// ————————————————————————————————————————————————————————————————
// 分润明细 → 结算单 → 分润统计：一条数据流，三处口径必须同源
//
// 明细（shareRecords）是**唯一的原始账**：结算单金额 = 该 (对象, 周期) 下明细之和，
// 分润统计的分润额同样是明细之和、已结算额则是该周期非草稿结算单之和。
// 三张表都不独立造数，否则「结算单 800、明细加起来 620」这种对不上的数会直接骗到财务。
// ————————————————————————————————————————————————————————————————
const SHARE_PERIODS = ["2026-07", "2026-06", "2026-05"];
/** 分成方一律引用现有主数据：场地方取 venues（VEN3xx），代理商取 agents（AG00x）。 */
const SHARE_PAYEES: { dimension: ShareRecord["dimension"]; payeeNo: string; payeeName: string }[] = [
  ...venues.map((v) => ({ dimension: "VENUE" as const, payeeNo: v.venueNo, payeeName: v.name })),
  ...agents.slice(0, 6).map((a) => ({ dimension: "AGENT" as const, payeeNo: a.agentNo, payeeName: a.name })),
];
const RECORDS_PER_PAYEE = 3;
export const shareRecords: ShareRecord[] = SHARE_PERIODS.flatMap((period, pi) =>
  SHARE_PAYEES.flatMap((payee, i) =>
    Array.from({ length: RECORDS_PER_PAYEE }, (_, k) => {
      const seq = (pi * SHARE_PAYEES.length + i) * RECORDS_PER_PAYEE + k;
      // 分成比例沿用各自域口径：场地方 15~25%，代理商 30~40%
      const rate = payee.dimension === "VENUE" ? [0.15, 0.2, 0.25][i % 3] : [0.3, 0.35, 0.4][i % 3];
      const gmv = 40 + (seq * 13) % 260; // 该笔结算批次对应的交易额
      return {
        recordNo: `SREC${9000 + seq}`,
        orderNo: orders[(seq * 7) % orders.length].orderNo,
        dimension: payee.dimension, payeeNo: payee.payeeNo, payeeName: payee.payeeName,
        amount: Number((gmv * rate).toFixed(2)), rate, currency: "AED",
        period,
        // 明细时间必须落在它声明的周期内，否则「按周期汇总」和「按时间筛选」会互相打脸
        createdAt: `${period}-${String(4 + k * 8).padStart(2, "0")}T${String(9 + (i % 8)).padStart(2, "0")}:20:00.000Z`,
      };
    })));

/** 按 (对象类型, 对象号, 周期) 汇总分润明细 —— **结算单金额的唯一口径**，详情抽屉逐笔展示同一批行。 */
export function aggregateShareRecords(payeeType: Settlement["payeeType"], payeeNo: string, period: string) {
  const rows = shareRecords.filter((r) => r.dimension === payeeType && r.payeeNo === payeeNo && r.period === period);
  return {
    rows,
    recordCount: rows.length,
    totalAmount: Number(rows.reduce((s, r) => s + r.amount, 0).toFixed(2)),
    currency: rows[0]?.currency ?? "AED",
  };
}

// 种子结算单：只出 2026-05（全部已打款）与 2026-06（部分待确认），**留出 2026-07 让运营真去生成一次**。
export const settlements: Settlement[] = ["2026-05", "2026-06"].flatMap((period, pi) =>
  SHARE_PAYEES.map((payee, i) => {
    const agg = aggregateShareRecords(payee.dimension, payee.payeeNo, period);
    const status: SettlementStatus = pi === 0 ? "PAID" : i % 3 === 0 ? "DRAFT" : "CONFIRMED";
    const createdAt = `${period}-28T20:00:00.000Z`;
    return {
      settleNo: `STL${700 + pi * SHARE_PAYEES.length + i}`,
      payeeType: payee.dimension, payeeNo: payee.payeeNo, payeeName: payee.payeeName,
      period, totalAmount: agg.totalAmount, recordCount: agg.recordCount, currency: agg.currency,
      status, createdAt,
      confirmedBy: status === "DRAFT" ? null : p(["Sara Ahmed", "Omar Khan", "admin"], i),
      confirmedAt: status === "DRAFT" ? null : `${period}-29T06:30:00.000Z`,
    };
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

// —— 对账 / 发票 ——（分润明细 shareRecords 在上方与结算单同源定义）
// 差错处置的种子分布：每 3 期出一次差错、正负交替（+ = 渠道多/账务少记，− = 账务多记），
// 处置进度覆盖 待处理 / 处理中 / 已结案 三档，页面一进来就能看到四种动作各自的去向。
const RECON_SEEDED: Record<Exclude<ReconHandleStatus, "OPEN">, { result: ReconHandleResult; note: string }> = {
  HANDLING: { result: "CHANNEL_ERROR", note: "已向 nearpay 提交差错工单 NP-2026-0417，挂起等待渠道回执" },
  RESOLVED: { result: "COMPENSATED", note: "按差额发起补差单 ADJ-2026-0033，平台侧已补记账并复核平账" },
  IGNORED: { result: "VERIFIED_OK", note: "逐笔核对无误：差额来自跨日切分，次日批次已自动冲平" },
};
export const reconciles: Reconcile[] = Array.from({ length: 12 }, (_, i) => {
  const nearpay = 30000 + (i * 3137) % 50000;
  const diff = i % 3 === 0 ? (i % 2 === 0 ? 1 : -1) * (12 + (i * 7) % 90) : 0;
  const handleStatus: ReconHandleStatus | null =
    diff === 0 ? null : p(["OPEN", "OPEN", "HANDLING", "RESOLVED"] as const, i);
  const seeded = handleStatus && handleStatus !== "OPEN" ? RECON_SEEDED[handleStatus] : null;
  return {
    batchNo: `RC${2026000 + i}`, period: `2026-${String((i % 12) + 1).padStart(2, "0")}`,
    nearpayTotal: nearpay, ledgerTotal: nearpay - diff, diff, currency: "AED",
    status: diff === 0 ? "MATCHED" : "DIFF", createdAt: iso(i * 86400_000),
    handleStatus,
    handleResult: seeded?.result ?? null,
    handleNote: seeded?.note ?? null,
    handledBy: seeded ? p(["Sara Ahmed", "Omar Khan", "admin"], i) : null,
    handledAt: seeded ? iso(i * 86400_000 - 10800_000) : null,
  };
});

// 发票：**金额不自造**，一律挂在一张结算单上（sourceNo），金额/抬头/币种取自该结算单——
// 「已开具发票 8000、对应结算单 620」这种数是骗财务的，开具时还会再校验一次（issueInvoice）。
const INVOICE_VOID_REASONS = ["抬头填错，需重开", "客户取消开票需求", "税号有误，重新登记后再开"];
/** 发票代码：税区 + 年度批次，10 位（同一年份同一批，开具时按当年生成）。 */
const invoiceCodeOfYear = (year: number) => `04${year}0001`;
/** 发票号码：8 位流水，落库时取现有最大值 +1（不用 length，避免删改后撞号）。 */
const INVOICE_NUMBER_BASE = 20260000;
export const invoices: Invoice[] = Array.from({ length: 18 }, (_, i) => {
  const src = settlements[(i * 3) % settlements.length];
  const status = p(["DRAFT", "ISSUED", "ISSUED", "VOID"] as const, i);
  const issued = status !== "DRAFT";
  const issuedAt = issued ? iso(i * 172800_000) : null;
  return {
    invoiceNo: `INV${2026000 + i}`, payeeName: src.payeeName,
    amount: src.totalAmount, vatTrn: `100${String(1000000000000 + i * 137).slice(0, 12)}`,
    currency: src.currency, status,
    sourceType: "SETTLEMENT" as const, sourceNo: src.settleNo,
    invoiceCode: issued ? invoiceCodeOfYear(2026) : null,
    invoiceNumber: issued ? String(INVOICE_NUMBER_BASE + i) : null,
    issuedAt, issuedBy: issued ? p(["Sara Ahmed", "Omar Khan", "admin"], i) : null,
    voidedAt: status === "VOID" ? iso(i * 172800_000 - 86400_000) : null,
    voidedBy: status === "VOID" ? p(["Sara Ahmed", "admin"], i) : null,
    voidReason: status === "VOID" ? p(INVOICE_VOID_REASONS, i) : null,
  };
});

export type ShareRecordQuery = PageQuery & { dimension?: string; payeeNo?: string; period?: string };
export const listShareRecords = (q: ShareRecordQuery = {}) =>
  paginate(shareRecords, q.page, q.size, (x) =>
    kwHit(q.keyword, x.recordNo, x.orderNo, x.payeeNo, x.payeeName) &&
    (!q.dimension || x.dimension === q.dimension) &&
    (!q.payeeNo || x.payeeNo === q.payeeNo) &&
    (!q.period || x.period === q.period));
export type ReconQuery = PageQuery & { status?: string; handleStatus?: string };
export const listReconciles = (q: ReconQuery = {}) =>
  paginate(reconciles, q.page, q.size, (x) =>
    // 搜索域随可见列一起扩：处理人/处理结论也要能搜（规格 §17.1-9）
    kwHit(q.keyword, x.batchNo, x.period, x.handledBy, x.handleNote) &&
    (!q.status || x.status === q.status) &&
    (!q.handleStatus || x.handleStatus === q.handleStatus));

export type InvoiceQuery = PageQuery & { status?: string };
export const listInvoices = (q: InvoiceQuery = {}) =>
  paginate(invoices, q.page, q.size, (x) =>
    kwHit(q.keyword, x.invoiceNo, x.payeeName, x.vatTrn, x.sourceNo, x.invoiceCode, x.invoiceNumber) &&
    (!q.status || x.status === q.status));

export const saveShareRule = (x: Partial<ShareRule>) => upsert(shareRules, x, "ruleNo", () => nextNo("SR", shareRules));

// ————————————————————————————————————————————————————————————————
// 对账差错处理（S2）：OPEN → HANDLING → RESOLVED / IGNORED
// 状态机定义在 lib/types/finance.ts（页面按钮与本层校验共用同一份），本层**强制执行**。
// 汇总（getReconStats）与列表同源于 `reconciles` 数组，所以处理完一笔，未结笔数/金额当场变。
// ————————————————————————————————————————————————————————————————

/** 对账差错处置违规（已平账 / 非法迁移 / 结论未填）。 */
export class ReconError extends Error {
  constructor(msg: string) { super(msg); this.name = "ReconError"; }
}

const RECON_STATUS_LABEL: Record<ReconHandleStatus, string> = {
  OPEN: "待处理", HANDLING: "处理中", RESOLVED: "已结案", IGNORED: "已忽略",
};

const findRecon = (batchNo: string) => {
  const r = reconciles.find((x) => x.batchNo === batchNo);
  if (!r) throw new ReconError(`对账批次 ${batchNo} 不存在`);
  return r;
};

/**
 * 差错处置。四道闸门：
 *  ① 批次必须真有差错（已平批次没有可处置对象）；② 动作必须是四个已声明动作之一；
 *  ③ 状态机允许（终态不可再动）；④ **结论必填**——差错处置是钱的定责，没结论等于没处理。
 */
export function handleRecon(
  batchNo: string, action: ReconAction, handleNote?: string, operatorName?: string,
): Reconcile {
  const r = findRecon(batchNo);
  if (r.status !== "DIFF" || r.handleStatus === null) {
    throw new ReconError(`对账批次 ${batchNo} 跑批结果为已平账，没有差错可处理`);
  }
  const tr = RECON_TRANSITIONS[action];
  if (!tr) throw new ReconError(`未知的差错处理动作：${action}`);
  if (!canReconTransition(r.handleStatus, action)) {
    throw new ReconError(
      `差错 ${batchNo} 当前「${RECON_STATUS_LABEL[r.handleStatus]}」不允许执行「${tr.label}」`
      + (RECON_TERMINAL.includes(r.handleStatus) ? "——已结案的差错不可再处置，如需翻案请重新跑批" : ""),
    );
  }
  const note = (handleNote ?? "").trim();
  if (!note) throw new ReconError("处理结论必填——写清依据（差额构成、凭证号/补差单号、对接人），否则无从复盘");

  Object.assign(r, {
    handleStatus: tr.to, handleResult: tr.result, handleNote: note,
    handledBy: operatorName || "admin", handledAt: new Date().toISOString(),
  });
  return r;
}

/** 对账汇总：全量口径、实时重算（不腌数字），所以「差错笔数/差错金额」跟着处置动作当场变。 */
export function getReconStats(): ReconStats {
  const diffs = reconciles.filter((x) => x.status === "DIFF" && x.handleStatus !== null);
  const open = diffs.filter((x) => x.handleStatus === "OPEN");
  const handling = diffs.filter((x) => x.handleStatus === "HANDLING");
  const closed = diffs.filter((x) => RECON_TERMINAL.includes(x.handleStatus!));
  const sumAbs = (rows: Reconcile[]) => Number(rows.reduce((s, x) => s + Math.abs(x.diff), 0).toFixed(2));
  return {
    batchCount: reconciles.length,
    matchedCount: reconciles.filter((x) => x.status === "MATCHED").length,
    diffCount: open.length + handling.length,
    diffAmount: sumAbs([...open, ...handling]),
    openCount: open.length,
    handlingCount: handling.length,
    closedCount: closed.length,
    closedAmount: sumAbs(closed),
    currency: reconciles[0]?.currency ?? "AED",
  };
}

// ————————————————————————————————————————————————————————————————
// 发票开具 / 作废（S2）：DRAFT → ISSUED → VOID
// 三条硬规矩：① 开具后抬头/金额锁死（saveInvoice 直接拒）；
//            ② 开具时金额必须与来源结算单对得上；③ 作废原因必填。
// ————————————————————————————————————————————————————————————————

/** 发票业务规则违规（非法迁移 / 已开具改数 / 作废未填原因 / 来源单据对不上）。 */
export class InvoiceError extends Error {
  constructor(msg: string) { super(msg); this.name = "InvoiceError"; }
}

const INV_STATUS_LABEL: Record<InvoiceStatus, string> = { DRAFT: "草稿", ISSUED: "已开具", VOID: "已作废" };

const findInvoice = (invoiceNo: string) => {
  const inv = invoices.find((x) => x.invoiceNo === invoiceNo);
  if (!inv) throw new InvoiceError(`发票 ${invoiceNo} 不存在`);
  return inv;
};
/** 来源结算单：发票金额的唯一出处。找不到就不许开票（宁可挡住，不许开一张对不上账的票）。 */
const invoiceSource = (inv: Pick<Invoice, "sourceNo">) => settlements.find((s) => s.settleNo === inv.sourceNo);

/**
 * 登记 / 编辑发票（草稿态）。开具与作废**不走这里**，只能走 issueInvoice / voidInvoice：
 * 状态、发票代码号码、开具与作废留痕都是服务端写的，表单送什么都一律丢弃。
 */
export function saveInvoice(x: Partial<Invoice>): Invoice {
  const {
    status: _st, invoiceCode: _c, invoiceNumber: _n,
    issuedAt: _ia, issuedBy: _ib, voidedAt: _va, voidedBy: _vb, voidReason: _vr,
    ...editable
  } = x;
  if (editable.invoiceNo) {
    const cur = findInvoice(editable.invoiceNo);
    if (!canEditInvoiceFields(cur.status)) {
      throw new InvoiceError(
        `发票 ${cur.invoiceNo} 已${INV_STATUS_LABEL[cur.status]}，抬头与金额不可再改`
        + (cur.status === "ISSUED" ? "——如需更正请先作废原票再重开" : "——已作废的票是历史记录"),
      );
    }
  }
  const sourceNo = String(editable.sourceNo ?? "").trim();
  if (!sourceNo) throw new InvoiceError("来源结算单必填——发票金额只能来自已确认的结算单，不凭空开票");
  const src = invoiceSource({ sourceNo });
  if (!src) throw new InvoiceError(`来源结算单 ${sourceNo} 不存在`);
  if (src.status === "DRAFT") throw new InvoiceError(`结算单 ${sourceNo} 尚未确认（待确认），确认后才能开票`);

  return upsert(
    invoices,
    {
      ...editable, sourceNo, sourceType: "SETTLEMENT",
      // 新增一律落草稿：开具必须是一个显式动作（要生成发票代码/号码并留痕）
      ...(editable.invoiceNo ? {} : {
        status: "DRAFT" as const, invoiceCode: null, invoiceNumber: null,
        issuedAt: null, issuedBy: null, voidedAt: null, voidedBy: null, voidReason: null,
      }),
    },
    "invoiceNo",
    () => nextNo("INV", invoices, 2026000, "invoiceNo"),
  );
}

/** 统一迁移入口：发票所有状态变更都必须走这里，不允许别处直接写 `inv.status = ...`。 */
function transitionInvoice(inv: Invoice, action: InvoiceAction, patch: Partial<Invoice>): Invoice {
  if (!canInvoiceTransition(inv.status, action)) {
    const tr = INV_TRANSITIONS[action];
    throw new InvoiceError(
      `发票 ${inv.invoiceNo} 当前「${INV_STATUS_LABEL[inv.status]}」不允许执行「${tr.label}」`
      + (action === "void" && inv.status === "DRAFT" ? "——草稿尚未进账，直接编辑或归档即可，不必占用作废号" : ""),
    );
  }
  Object.assign(inv, patch, { status: INV_TRANSITIONS[action].to });
  return inv;
}

/** 开具：DRAFT → ISSUED，生成发票代码/号码并留痕；金额必须与来源结算单一致。 */
export function issueInvoice(invoiceNo: string, operatorName?: string): Invoice {
  const inv = findInvoice(invoiceNo);
  if (!canInvoiceTransition(inv.status, "issue")) transitionInvoice(inv, "issue", {}); // 复用同一段报错文案
  const src = invoiceSource(inv);
  if (!src) throw new InvoiceError(`来源结算单 ${inv.sourceNo} 不存在，无法开具`);
  if (Math.abs(src.totalAmount - inv.amount) > 0.005) {
    throw new InvoiceError(
      `开票金额 ${inv.amount} ${inv.currency} 与来源结算单 ${src.settleNo} 的 ${src.totalAmount} ${src.currency} 对不上，`
      + "开具前必须先对平——已开具的票金额就锁死了",
    );
  }
  const maxNumber = invoices.reduce((m, x) => Math.max(m, Number(x.invoiceNumber ?? 0)), INVOICE_NUMBER_BASE);
  const now = new Date();
  return transitionInvoice(inv, "issue", {
    invoiceCode: invoiceCodeOfYear(now.getUTCFullYear()),
    invoiceNumber: String(maxNumber + 1),
    issuedAt: now.toISOString(),
    issuedBy: operatorName || "admin",
  });
}

/** 作废：ISSUED → VOID，**原因必填**（不可逆，页面另有二次确认）。 */
export function voidInvoice(invoiceNo: string, voidReason?: string, operatorName?: string): Invoice {
  const inv = findInvoice(invoiceNo);
  if (!canInvoiceTransition(inv.status, "void")) transitionInvoice(inv, "void", {});
  const reason = (voidReason ?? "").trim();
  if (!reason) throw new InvoiceError("作废原因必填——作废等于账面凭空少一张票，不留原因日后无从解释");
  return transitionInvoice(inv, "void", {
    voidedAt: new Date().toISOString(), voidedBy: operatorName || "admin", voidReason: reason,
  });
}

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

// ————————————————————————————————————————————————————————————————
// 结算单：生成（DRAFT）→ 确认（CONFIRMED）→ 打款（PAID）
// 状态机定义在 lib/types/finance.ts（页面按钮与本层校验共用同一份），本层**强制执行**：
// 非法迁移抛错，绝不默默通过；金额一律来自 aggregateShareRecords，绝不凭空造数。
// ————————————————————————————————————————————————————————————————

/** 结算单业务规则违规（幂等冲突 / 无明细 / 非法状态迁移）。 */
export class SettlementError extends Error {
  constructor(msg: string) { super(msg); this.name = "SettlementError"; }
}

const PERIOD_RE = /^\d{4}-(0[1-9]|1[0-2])$/;
const settlementPayeeName = (payeeType: Settlement["payeeType"], payeeNo: string) => {
  const name = payeeType === "VENUE"
    ? venues.find((v) => v.venueNo === payeeNo)?.name
    : agents.find((a) => a.agentNo === payeeNo)?.name;
  if (!name) throw new SettlementError(`结算对象 ${payeeNo} 不存在（${payeeType === "VENUE" ? "场地方" : "代理商"}）`);
  return name;
};
const findSettlement = (settleNo: string) => {
  const s = settlements.find((x) => x.settleNo === settleNo);
  if (!s) throw new SettlementError(`结算单 ${settleNo} 不存在`);
  return s;
};

export type SettlementQuery = PageQuery & { status?: string; payeeType?: string; period?: string };
export const listSettlements = (q: SettlementQuery = {}) =>
  paginate(settlements, q.page, q.size, (x) =>
    kwHit(q.keyword, x.settleNo, x.payeeNo, x.payeeName, x.period, x.confirmedBy) &&
    (!q.status || x.status === q.status) &&
    (!q.payeeType || x.payeeType === q.payeeType) &&
    (!q.period || x.period === q.period));

/**
 * 生成结算单。四道闸门，任一不过整批拒绝（不做半成功，出账不能留半截）：
 *  ① 周期格式必须 `YYYY-MM`；② 至少选一个对象；
 *  ③ **幂等**：同 对象+周期 已有结算单（任何状态）一律拒绝，杜绝重复出账；
 *  ④ 该周期无分润明细的对象拒绝——金额只能来自明细汇总。
 */
export function generateSettlements(x: SettlementDraft): Settlement[] {
  const payeeType = x.payeeType;
  const period = x.period ?? "";
  if (payeeType !== "VENUE" && payeeType !== "AGENT") throw new SettlementError("结算对象类型必填（场地方 / 代理商）");
  if (!PERIOD_RE.test(period)) throw new SettlementError(`结算周期格式不对：「${period}」，应形如 2026-07`);
  const payeeNos = [...new Set((x.payeeNos ?? []).map((s) => s.trim()).filter(Boolean))];
  if (!payeeNos.length) throw new SettlementError("请至少选择一个结算对象");

  const names = payeeNos.map((no) => settlementPayeeName(payeeType, no));
  const dup = payeeNos.filter((no) => settlements.some((s) => s.payeeType === payeeType && s.payeeNo === no && s.period === period));
  if (dup.length) {
    const detail = dup.map((no) => {
      const s = settlements.find((y) => y.payeeType === payeeType && y.payeeNo === no && y.period === period)!;
      return `${no}→${s.settleNo}(${s.status})`;
    }).join("、");
    throw new SettlementError(`${period} 这些对象已生成过结算单：${detail}——同一对象同一周期只能出一次账，如需重算请先作废原单`);
  }
  const empty = payeeNos.filter((no) => aggregateShareRecords(payeeType, no, period).recordCount === 0);
  if (empty.length) throw new SettlementError(`${period} 无分润明细可结算：${empty.join("、")}——结算金额只能来自分润明细汇总，不凭空出单`);

  const created = payeeNos.map((no, i) => {
    const agg = aggregateShareRecords(payeeType, no, period);
    const s: Settlement = {
      settleNo: nextNo("STL", settlements, 700, "settleNo"),
      payeeType, payeeNo: no, payeeName: names[i], period,
      totalAmount: agg.totalAmount, recordCount: agg.recordCount, currency: agg.currency,
      status: "DRAFT", createdAt: new Date().toISOString(), confirmedBy: null, confirmedAt: null,
    };
    settlements.unshift(s);
    return s;
  });
  return created;
}

/** 统一迁移入口：所有结算单状态变更都必须走这里，不允许别处直接写 `s.status = ...`。 */
export function transitionSettlement(settleNo: string, action: SettlementAction, patch: Partial<Settlement> = {}): Settlement {
  const s = findSettlement(settleNo);
  if (!canSettlementTransition(s.status, action)) {
    throw new SettlementError(`结算单 ${settleNo} 当前状态「${s.status}」不允许执行「${STL_TRANSITIONS[action].label}」`);
  }
  Object.assign(s, patch, { status: STL_TRANSITIONS[action].to });
  return s;
}

/** 确认结算：DRAFT → CONFIRMED，记确认人/确认时间（确认后金额锁定，进入应付）。 */
export const confirmSettlement = (settleNo: string, operatorName?: string) =>
  transitionSettlement(settleNo, "confirm", {
    confirmedBy: operatorName || "admin", confirmedAt: new Date().toISOString(),
  });

/** 结算单构成明细：钱是怎么来的，逐笔可查（与生成时的汇总口径同一个函数）。 */
export const listSettlementRecords = (settleNo: string, q: PageQuery = {}) => {
  const s = findSettlement(settleNo);
  return paginate(aggregateShareRecords(s.payeeType, s.payeeNo, s.period).rows, q.page, q.size ?? 50);
};

// —— §5 分润统计 ——
// 全部**派生自分润明细**：订单数 = 去重订单号数，交易额 = Σ(明细金额 / 比例)，分润额 = Σ明细金额。
// 已结算额 = 该 (对象, 周期) 下**非草稿**结算单金额之和 —— 所以「确认结算」一按，
// 这张表的待结算就会跟着降，三页口径不会各说各话（规格 §17.1-8）。
export const shareSummaries: ShareSummary[] = SHARE_PERIODS.flatMap((period) =>
  SHARE_PAYEES.map((payee) => {
    const agg = aggregateShareRecords(payee.dimension, payee.payeeNo, period);
    return {
      ...payee, period,
      orderCount: new Set(agg.rows.map((r) => r.orderNo)).size,
      gmv: Number(agg.rows.reduce((s, r) => s + r.amount / r.rate, 0).toFixed(2)),
      shareAmount: agg.totalAmount,
      settledAmount: 0, // 由 refreshSettledAmounts() 实时算，见下
      pendingAmount: agg.totalAmount,
      currency: agg.currency,
    };
  }),
);

/**
 * 刷新已结算/待结算：结算单状态会变（生成/确认），故每次查询前重算，
 * 而不是把当时的数字腌在数组里——否则「刚确认完统计不动」。结构不变，只更新两列金额。
 */
function refreshSettledAmounts(): void {
  for (const s of shareSummaries) {
    const settled = settlements
      .filter((x) => x.payeeType === s.dimension && x.payeeNo === s.payeeNo && x.period === s.period && x.status !== "DRAFT")
      .reduce((sum, x) => sum + x.totalAmount, 0);
    // 已结算不可能超过分润额（同源汇总本来就相等，取 min 是兜住手工改数的意外）
    s.settledAmount = Number(Math.min(settled, s.shareAmount).toFixed(2));
    s.pendingAmount = Number((s.shareAmount - s.settledAmount).toFixed(2));
  }
}
refreshSettledAmounts();

export type ShareSummaryQuery = PageQuery & {
  dimension?: string; // VENUE / AGENT（维度切换器）
  period?: string;
  sortKey?: string; // 目前仅 shareAmount / pendingAmount / gmv / orderCount
  sortDir?: string; // asc / desc
};
export const listShareSummaries = (q: ShareSummaryQuery = {}) => {
  refreshSettledAmounts();
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
