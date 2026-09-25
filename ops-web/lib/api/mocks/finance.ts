// 覆盖范围：分账规则与流水、总账、结算、提现审批、对账、发票、分润统计、充值订单。
import * as db from "../../mock/db";
import * as sa from "../../mock/db/settlement-adjust";
// 走具体模块不改变行为——同一份模块级状态，只是绕过桶文件
import type { FinanceApi } from "../contracts/finance";
import type { PageQ, ShareRuleQ, ShareSummaryQ, RechargeQ, SettlementQ, ShareRecordQ, ReconQ, InvoiceQ , ReportQ, WithdrawalQ } from "../query";
import { wait } from "./_wait";

export const financeMock: FinanceApi = {
  // 收款账户（B3）：写操作走 db 层（「恰好一个默认」与「默认不能直接停」都在那强制）
  listPayoutAccounts: (q = {}) => wait(db.listPayoutAccounts(q)),
  savePayoutAccount: (x) => wait(db.savePayoutAccount(x), 350),
  disablePayoutAccount: (no) => wait(db.disablePayoutAccount(no), 350),

  // 视角（dimension）在这一层筛：与分润统计同口径，翻页/搜索都在筛过之后进行
  listShareRules: (q: ShareRuleQ = {}) => wait(db.paginate(db.shareRules, q.page, q.size,
    (s) => (!q.dimension || s.dimension === q.dimension) && db.kwHit(q.keyword, s.payeeName, s.ruleNo))),
  // 期间筛选下沉到 db 层（listLedgerInPeriod）：窗口换算与报表域同源，页面不自己算日期
  listLedger: (q: ReportQ = {}) => wait(db.listLedgerInPeriod(q)),
  // 凭证下钻：借贷合计与平衡判定由 db 层算，前端不心算 —— 判定口径只有一处
  createVoucher: (x) => wait(db.createVoucher(x), 400),
  getVoucher: (no) => wait({ voucherNo: no, entries: db.listVoucherEntries(no), ...db.voucherBalance(no) }),
  listSettlements: (q: SettlementQ = {}) => wait(db.listSettlements(q)),
  // payeeNo 过滤：代理端「我的提现」用它。真后端另有 AGENT 数据范围硬过滤兜底，
  // 这里补上是为了让 mock 下的代理视图也只显示自己的单子 —— 否则页面看着像越权
  listWithdrawals: (q: WithdrawalQ = {}) => wait(db.paginate(db.withdrawals, q.page, q.size, (w) =>
    db.kwHit(q.keyword, w.payeeName, w.withdrawNo, w.auditorName)
    && (!q.payeeNo || w.payeeNo === q.payeeNo)
    && (!q.status || w.status === q.status))),
  applyWithdrawal: (body) => wait(db.applyWithdrawal(body), 400),
  auditWithdrawal: (no, approve, rejectReason, auditorName) => wait(db.auditWithdrawal(no, approve, rejectReason, auditorName), 400),
  payWithdrawal: (no, body) => wait(db.payWithdrawal(no, body), 400),

  // S1 结算单闭环：校验（幂等/无明细/状态机）全在 db 层，错误由全局 MutationCache 弹出
  generateSettlements: (x) => wait(db.generateSettlements(x), 500),
  confirmSettlement: (no, operatorName) => wait(db.confirmSettlement(no, operatorName), 400),
  listSettlementRecords: (no, q: PageQ = {}) => wait(db.listSettlementRecords(no, q)),
  getSettlement: async (no) => wait(sa.getSettlementView(no)),
  getSettlementStatement: async (no) => wait(sa.getSettlementStatement(no)),
  getSettlementStatementHtml: async (no, lang) => wait(sa.settlementStatementHtml(no, lang), 300),

  // 结算调整项：确认 / 作废的状态机与必填在 db 层（settlement-adjust.ts）强制
  listSettlementAdjustments: async (q = {}) => wait(sa.listSettlementAdjustments(q)),
  confirmSettlementAdjustment: async (no, body) => wait(sa.confirmSettlementAdjustment(no, body), 400),
  voidSettlementAdjustment: async (no, reason) => wait(sa.voidSettlementAdjustment(no, reason), 400),

  // 财务扩展
  listShareRecords: (q: ShareRecordQ = {}) => wait(db.listShareRecords(q)),
  listReconciles: (q: ReconQ = {}) => wait(db.listReconciles(q)),
  listInvoices: (q: InvoiceQ = {}) => wait(db.listInvoices(q)),
  // async：理由同 getInventoryTransfer —— 同步抛出的 404 不会变成 rejected promise
  getInvoice: async (invoiceNo) => wait(db.getInvoice(invoiceNo)),
  // async：让校验抛的 ApiError 变成 rejected promise，交给全局 MutationCache 弹错
  //（同 saveSite 的理由——同步抛出时那条「请选择分成方」根本到不了界面上）
  saveShareRule: async (x) => wait(db.saveShareRule(x), 350),
  saveInvoice: (x) => wait(db.saveInvoice(x), 350),

  // S2：差错处置 / 发票开具作废——状态机、必填结论与原因、金额对平全在 db 层，
  // 错误由全局 MutationCache 弹出，页面不重复兜底
  listReconDiffs: (no) => wait(db.listReconDiffs(no)),
  handleRecon: (no, action, note, operatorName, diffId) => wait(db.handleRecon(no, action, note, operatorName, diffId), 400),
  getReconStats: () => wait(db.getReconStats()),
  issueInvoice: (no, operatorName) => wait(db.issueInvoice(no, operatorName), 400),
  voidInvoice: (no, voidReason, operatorName) => wait(db.voidInvoice(no, voidReason, operatorName), 400),

  // 财务 B5：分润统计（维度/周期/排序在 db 层处理）/ 充值订单
  listShareSummaries: (q: ShareSummaryQ = {}) => wait(db.listShareSummaries(q)),
  listRechargeOrders: (q: RechargeQ = {}) => wait(db.listRechargeOrders(q)),
};
