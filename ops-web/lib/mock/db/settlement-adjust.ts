// 结算调整项（C9 / G2）+ 结算单详情 + 场地方对账单（G3）的 mock，
// 对齐后端 AdjustmentServiceImpl / FinanceController#settlement / StatementServiceImpl。
//
// 调整项与后端逐条一致的规则：
//  · 确认只认 PENDING；金额可改，**改了必须写说明**（与系统建议值不同就是「改了」）；
//  · 作废认 PENDING / CONFIRMED，**原因必填**；SETTLED（已并入结算单）不能作废 —— 那张单的钱已经算进去了；
//  · 说明与作废原因追加在 note 后面（「；确认：…」「；作废：…」），不覆盖系统写的依据。
import type {
  Adjustment, AdjustmentConfirmPayload, AdjustmentKind, AdjustmentSource, AdjustmentStatus,
  PageQuery, PageResult, SettlementView, Statement, StatementAdjustLine, StatementLang, StatementShareLine,
} from "../../types";
import { ADJUSTMENT_TRANSITIONS, canAdjustmentTransition } from "../../types";
import { venues, contracts } from "./location";
import { settlements, aggregateShareRecords } from "./finance";
import { paginate, kwHit, nextNo } from "./helpers";
import { fail, notFound } from "@/lib/biz-error";

const r2 = (n: number) => Math.round(n * 100) / 100;

function seed(): Adjustment[] {
  const rows: Adjustment[] = [];
  const mk = (i: number, kind: AdjustmentKind, source: AdjustmentSource, status: AdjustmentStatus, amount: number, note: string): Adjustment => {
    const c = contracts[i % contracts.length];
    const v = venues.find((x) => x.venueNo === c.venueNo) ?? venues[0];
    const confirmed = status !== "PENDING";
    return {
      adjNo: `ADJ${9100 + rows.length}`,
      payeeType: "VENUE", payeeNo: v.venueNo, payeeName: v.name,
      kind,
      // 保底补差按账期生成，一次性的（撤场结清）为空串 —— 与后端 stl_adjustment.period 同义
      period: kind === "GUARANTEE_TOPUP" ? "2026-09" : "",
      siteNo: c.siteNo ?? null, contractNo: c.contractNo,
      amount, suggestedAmount: amount, currency: "AED", status,
      settleNo: status === "SETTLED" ? settlements.find((s) => s.payeeNo === v.venueNo)?.settleNo ?? null : null,
      source, note,
      confirmedBy: confirmed && status !== "VOID" ? (source === "GUARANTEE" ? "SYSTEM" : "Sara Ahmed") : null,
      confirmedAt: confirmed && status !== "VOID" ? "2026-06-30T08:00:00" : null,
      createdAt: `2026-06-${String(10 + rows.length).padStart(2, "0")}T09:00:00`,
    };
  };
  // 撤场关闭按合同生成的两类建议值（负数 = 场地方应返还平台），待财务确认
  rows.push(mk(0, "DEPOSIT_REFUND", "SITE_CLOSED", "PENDING", -2000, "撤场退还押金（合同 CT400）；按合同退还条件确认金额"));
  rows.push(mk(0, "ENTRY_FEE_SETTLE", "SITE_CLOSED", "PENDING", -412.33, "提前撤场，进场费按剩余 92 / 365 天折算（合同 CT400）"));
  rows.push(mk(3, "DEPOSIT_REFUND", "SITE_CLOSED", "CONFIRMED", -1500, "撤场退还押金（合同 CT403）；确认：场地方扣除 500 设备安装押金，按实际退还"));
  // 保底补差：合同算出来的钱不需要人拍板，直接已确认（正数 = 平台补给场地方）
  rows.push(mk(1, "GUARANTEE_TOPUP", "GUARANTEE", "CONFIRMED", 356.8, "保底 1500.00（30 / 30 天）− 分成 1143.20"));
  rows.push(mk(2, "GUARANTEE_TOPUP", "GUARANTEE", "SETTLED", 220, "保底 1000.00（31 / 31 天）− 分成 780.00"));
  rows.push(mk(4, "ENTRY_FEE_SETTLE", "SITE_CLOSED", "VOID", -300, "提前撤场，进场费按剩余 30 / 365 天折算（合同 CT404）；作废：合同约定进场费不退"));
  return rows;
}

let rows: Adjustment[] | null = null;
/** 惰性铺种子：本目录模块之间有依赖环，顶层读 contracts / settlements 会在初始化期取到半成品。 */
export function adjustments(): Adjustment[] {
  if (!rows) rows = seed();
  return rows;
}

export type AdjustmentQuery = PageQuery & {
  status?: string; payeeNo?: string; siteNo?: string; kind?: string; source?: string;
};
export const listSettlementAdjustments = (q: AdjustmentQuery = {}): PageResult<Adjustment> =>
  paginate(adjustments(), q.page, q.size ?? 20, (a) =>
    kwHit(q.keyword, a.adjNo, a.payeeNo, a.payeeName, a.contractNo, a.siteNo)
    && (!q.status || a.status === q.status)
    && (!q.payeeNo || a.payeeNo === q.payeeNo)
    && (!q.siteNo || a.siteNo === q.siteNo)
    && (!q.kind || a.kind === q.kind)
    && (!q.source || a.source === q.source));

const requireAdj = (adjNo: string) => adjustments().find((a) => a.adjNo === adjNo) ?? notFound("调整项", "Adjustment", adjNo);

export function confirmSettlementAdjustment(adjNo: string, body: AdjustmentConfirmPayload = {}, operator = "admin"): Adjustment {
  const a = requireAdj(adjNo);
  if (!canAdjustmentTransition(a.status, "confirm")) {
    fail(`调整项 ${adjNo} 当前是「${a.status}」，只有待确认的能确认`, `Adjustment ${adjNo} is not pending`);
  }
  const finalAmount = body.amount == null || Number.isNaN(body.amount) ? a.amount : r2(body.amount);
  const base = a.suggestedAmount ?? a.amount;
  const note = body.note?.trim() ?? "";
  if (finalAmount !== base && !note) {
    fail("确认金额与系统建议值不同，必须写明原因", "A note is required when the amount differs from the suggestion", "يلزم كتابة سبب عند تغيير المبلغ");
  }
  a.status = ADJUSTMENT_TRANSITIONS.confirm.to;
  a.amount = finalAmount;
  a.confirmedBy = operator;
  a.confirmedAt = new Date().toISOString();
  if (note) a.note = `${a.note ?? ""}；确认：${note}`;
  return a;
}

export function voidSettlementAdjustment(adjNo: string, reason: string): Adjustment {
  if (!reason?.trim()) fail("作废必须写原因", "Reason is required", "السبب مطلوب");
  const a = requireAdj(adjNo);
  if (!canAdjustmentTransition(a.status, "void")) {
    fail(`调整项 ${adjNo} 当前是「${a.status}」，已并入结算单或已作废的不能再作废`, `Adjustment ${adjNo} cannot be voided`);
  }
  a.status = ADJUSTMENT_TRANSITIONS.void.to;
  a.note = `${a.note ?? ""}；作废：${reason.trim()}`;
  return a;
}

// —— 结算单详情 / 对账单 ——

const requireSettlement = (settleNo: string) =>
  settlements.find((s) => s.settleNo === settleNo) ?? notFound("结算单", "Settlement", settleNo);

/** 详情：本体 + 构成行（分润明细）。后端详情里 recordCount 为 null，这里照做，别让 mock 比真后端「更好用」。 */
export function getSettlementView(settleNo: string): SettlementView {
  const s = requireSettlement(settleNo);
  const agg = aggregateShareRecords(s.payeeType, s.payeeNo, s.period);
  return {
    settlement: { ...s, recordCount: null as unknown as number },
    details: [
      ...agg.rows.map((r) => ({ refType: "SHARE", refNo: r.recordNo, amount: r.amount })),
      ...adjustments().filter((a) => a.settleNo === settleNo).map((a) => ({ refType: "ADJUSTMENT", refNo: a.adjNo, amount: a.amount })),
    ],
  };
}

export function getSettlementStatement(settleNo: string): Statement {
  const s = requireSettlement(settleNo);
  const agg = aggregateShareRecords(s.payeeType, s.payeeNo, s.period);
  // 按「合同 × 比例」分组：mock 的分润明细不带合同号，用该场地方的首份合同代替（代理商为空）
  const contractNo = s.payeeType === "VENUE" ? contracts.find((c) => c.venueNo === s.payeeNo)?.contractNo ?? null : null;
  const byRate = new Map<number, StatementShareLine>();
  for (const r of agg.rows) {
    const cur = byRate.get(r.rate) ?? { contractNo, rate: r.rate, orders: 0, gross: 0, amount: 0 };
    cur.orders += 1;
    cur.gross = r2(cur.gross + r.grossAmount);
    cur.amount = r2(cur.amount + r.amount);
    byRate.set(r.rate, cur);
  }
  const shares = [...byRate.values()];
  const adjs: StatementAdjustLine[] = adjustments()
    .filter((a) => a.settleNo === settleNo)
    .map((a) => ({ adjNo: a.adjNo, kind: a.kind, contractNo: a.contractNo, siteNo: a.siteNo, period: s.period, amount: a.amount, note: a.note }));
  const shareTotal = r2(shares.reduce((t, x) => t + x.amount, 0));
  const adjustTotal = r2(adjs.reduce((t, x) => t + x.amount, 0));
  return {
    settleNo: s.settleNo, payeeType: s.payeeType, payeeNo: s.payeeNo, payeeName: s.payeeName,
    period: s.period, currency: s.currency, status: s.status,
    orderCount: new Set(agg.rows.map((r) => r.orderNo)).size,
    grossTotal: r2(shares.reduce((t, x) => t + x.gross, 0)),
    shareTotal, shares, adjustTotal, adjustments: adjs, total: r2(shareTotal + adjustTotal),
  };
}

const T: Record<StatementLang, Record<string, string>> = {
  zh: { title: "场地方对账单", payee: "收款方", period: "账期", no: "结算单号", orders: "订单数", gross: "订单收入", share: "分成",
    contract: "合同", rate: "比例", amount: "金额", adj: "调整项", kind: "类型", note: "说明", none: "无", total: "本期应付" },
  en: { title: "Venue Statement", payee: "Payee", period: "Period", no: "Settlement No.", orders: "Orders", gross: "Order revenue", share: "Revenue share",
    contract: "Contract", rate: "Rate", amount: "Amount", adj: "Adjustments", kind: "Type", note: "Note", none: "None", total: "Payable this period" },
  ar: { title: "كشف حساب الموقع", payee: "المستفيد", period: "الفترة", no: "رقم التسوية", orders: "الطلبات", gross: "إيراد الطلبات", share: "حصة الإيراد",
    contract: "العقد", rate: "النسبة", amount: "المبلغ", adj: "التعديلات", kind: "النوع", note: "ملاحظة", none: "لا يوجد", total: "المستحق لهذه الفترة" },
};
const esc = (v: unknown) => String(v ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;" }[c]!));

/** 可打印对账单（与后端同一个版式：浏览器「打印 → 存为 PDF」）。ar 右到左。 */
export function settlementStatementHtml(settleNo: string, lang: StatementLang = "zh"): string {
  const st = getSettlementStatement(settleNo);
  const t = T[lang] ?? T.zh;
  const m = (n: number) => `${n.toFixed(2)} ${esc(st.currency)}`;
  const shareRows = st.shares.map((x) =>
    `<tr><td>${esc(x.contractNo ?? "-")}</td><td class="num">${Math.round(x.rate * 100)}%</td><td class="num">${x.orders}</td><td class="num">${m(x.gross)}</td><td class="num">${m(x.amount)}</td></tr>`).join("");
  const adjRows = st.adjustments.length
    ? st.adjustments.map((x) => `<tr><td>${esc(x.kind)}</td><td>${esc(x.contractNo ?? "-")}</td><td>${esc(x.period ?? "-")}</td><td class="num">${m(x.amount)}</td><td>${esc(x.note ?? "")}</td></tr>`).join("")
    : `<tr><td colspan="5">${t.none}</td></tr>`;
  return `<!DOCTYPE html><html lang="${lang}" dir="${lang === "ar" ? "rtl" : "ltr"}"><head><meta charset="utf-8"><title>${t.title} ${esc(st.settleNo)}</title>`
    + `<style>body{font-family:system-ui,sans-serif;margin:32px}table{border-collapse:collapse;width:100%;margin:12px 0}th,td{border:1px solid #bbb;padding:6px 8px;text-align:start;font-size:13px}.num{text-align:end}@media print{body{margin:12mm}}</style></head><body>`
    + `<h1>${t.title}</h1><table><tr><th>${t.payee}</th><td>${esc(st.payeeName)} (${esc(st.payeeNo)})</td></tr><tr><th>${t.period}</th><td>${esc(st.period)}</td></tr>`
    + `<tr><th>${t.no}</th><td>${esc(st.settleNo)}</td></tr><tr><th>${t.orders}</th><td>${st.orderCount}</td></tr><tr><th>${t.gross}</th><td>${m(st.grossTotal)}</td></tr></table>`
    + `<h2>${t.share}</h2><table><tr><th>${t.contract}</th><th>${t.rate}</th><th>${t.orders}</th><th>${t.gross}</th><th>${t.amount}</th></tr>${shareRows}</table>`
    + `<h2>${t.adj}</h2><table><tr><th>${t.kind}</th><th>${t.contract}</th><th>${t.period}</th><th>${t.amount}</th><th>${t.note}</th></tr>${adjRows}</table>`
    + `<p><strong>${t.total}: ${m(st.total)}</strong></p></body></html>`;
}

/** 测试用：新建一条待确认调整项（撤场关闭生成的形状）。 */
export function _pushAdjustment(x: Partial<Adjustment> = {}): Adjustment {
  const a: Adjustment = {
    adjNo: nextNo("ADJ", adjustments(), 9100, "adjNo"),
    payeeType: "VENUE", payeeNo: venues[0].venueNo, payeeName: venues[0].name,
    kind: "DEPOSIT_REFUND", period: "", siteNo: null, contractNo: contracts[0].contractNo,
    amount: -1000, suggestedAmount: -1000, currency: "AED", status: "PENDING",
    settleNo: null, source: "SITE_CLOSED", note: "撤场退还押金", confirmedBy: null, confirmedAt: null,
    createdAt: new Date().toISOString(), ...x,
  };
  adjustments().unshift(a);
  return a;
}
