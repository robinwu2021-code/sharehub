// 覆盖范围：分账规则与流水、总账、结算、提现审批、对账、发票、分润统计、充值订单。
import * as db from "../../mock/db";
import type { FinanceApi } from "../contracts/finance";
import type { PageQ, ShareSummaryQ, RechargeQ, SettlementQ, ShareRecordQ, ReconQ, InvoiceQ } from "../query";
import { wait } from "./_wait";

export const financeMock: FinanceApi = {
  listShareRules: (q: PageQ = {}) => wait(db.paginate(db.shareRules, q.page, q.size, (s) => db.kwHit(q.keyword, s.payeeName))),
  listLedger: (q: PageQ = {}) => wait(db.paginate(db.ledger, q.page, q.size, (l) => db.kwHit(q.keyword, l.account, l.orderNo, l.voucherNo))),
  listSettlements: (q: SettlementQ = {}) => wait(db.listSettlements(q)),
  listWithdrawals: (q: PageQ = {}) => wait(db.paginate(db.withdrawals, q.page, q.size, (w) => db.kwHit(q.keyword, w.payeeName, w.withdrawNo, w.auditorName))),
  auditWithdrawal: (no, approve, rejectReason, auditorName) => wait(db.auditWithdrawal(no, approve, rejectReason, auditorName), 400),

  // S1 结算单闭环：校验（幂等/无明细/状态机）全在 db 层，错误由全局 MutationCache 弹出
  generateSettlements: (x) => wait(db.generateSettlements(x), 500),
  confirmSettlement: (no, operatorName) => wait(db.confirmSettlement(no, operatorName), 400),
  listSettlementRecords: (no, q: PageQ = {}) => wait(db.listSettlementRecords(no, q)),

  // 财务扩展
  listShareRecords: (q: ShareRecordQ = {}) => wait(db.listShareRecords(q)),
  listReconciles: (q: ReconQ = {}) => wait(db.listReconciles(q)),
  listInvoices: (q: InvoiceQ = {}) => wait(db.listInvoices(q)),
  saveShareRule: (x) => wait(db.saveShareRule(x), 350),
  saveInvoice: (x) => wait(db.saveInvoice(x), 350),

  // S2：差错处置 / 发票开具作废——状态机、必填结论与原因、金额对平全在 db 层，
  // 错误由全局 MutationCache 弹出，页面不重复兜底
  handleRecon: (no, action, note, operatorName) => wait(db.handleRecon(no, action, note, operatorName), 400),
  getReconStats: () => wait(db.getReconStats()),
  issueInvoice: (no, operatorName) => wait(db.issueInvoice(no, operatorName), 400),
  voidInvoice: (no, voidReason, operatorName) => wait(db.voidInvoice(no, voidReason, operatorName), 400),

  // 财务 B5：分润统计（维度/周期/排序在 db 层处理）/ 充值订单
  listShareSummaries: (q: ShareSummaryQ = {}) => wait(db.listShareSummaries(q)),
  listRechargeOrders: (q: RechargeQ = {}) => wait(db.listRechargeOrders(q)),
};
