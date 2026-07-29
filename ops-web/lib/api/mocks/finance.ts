// 覆盖范围：分账规则与流水、总账、结算、提现审批、对账、发票、分润统计、充值订单。
import * as db from "../../mock/db";
import type { FinanceApi } from "../contracts/finance";
import type { PageQ, ShareSummaryQ, RechargeQ } from "../query";
import { wait } from "./_wait";

export const financeMock: FinanceApi = {
  listShareRules: (q: PageQ = {}) => wait(db.paginate(db.shareRules, q.page, q.size, (s) => db.kwHit(q.keyword, s.payeeName))),
  listLedger: (q: PageQ = {}) => wait(db.paginate(db.ledger, q.page, q.size, (l) => db.kwHit(q.keyword, l.account, l.orderNo, l.voucherNo))),
  listSettlements: (q: PageQ = {}) => wait(db.paginate(db.settlements, q.page, q.size, (s) => db.kwHit(q.keyword, s.payeeName, s.settleNo))),
  listWithdrawals: (q: PageQ = {}) => wait(db.paginate(db.withdrawals, q.page, q.size, (w) => db.kwHit(q.keyword, w.payeeName, w.withdrawNo, w.auditorName))),
  auditWithdrawal: (no, approve, rejectReason, auditorName) => wait(db.auditWithdrawal(no, approve, rejectReason, auditorName), 400),

  // 财务扩展
  listShareRecords: (q: PageQ = {}) => wait(db.listShareRecords(q)),
  listReconciles: (q: PageQ = {}) => wait(db.listReconciles(q)),
  listInvoices: (q: PageQ = {}) => wait(db.listInvoices(q)),
  saveShareRule: (x) => wait(db.saveShareRule(x), 350),
  saveInvoice: (x) => wait(db.saveInvoice(x), 350),

  // 财务 B5：分润统计（维度/周期/排序在 db 层处理）/ 充值订单
  listShareSummaries: (q: ShareSummaryQ = {}) => wait(db.listShareSummaries(q)),
  listRechargeOrders: (q: RechargeQ = {}) => wait(db.listRechargeOrders(q)),
};
