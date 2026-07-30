// 覆盖范围：分账规则与流水、总账、结算、提现审批、对账、发票、分润统计、充值订单。
// 端点前缀：/api/trade/**；充值订单与钱包同主体，归 /api/user/**。
import { client } from "../http-client";
import type { FinanceApi } from "../contracts/finance";
import type { PageQ, ShareSummaryQ, RechargeQ, SettlementQ, ShareRecordQ } from "../query";

export const financeHttp: FinanceApi = {
  listShareRules: (q?: PageQ) => client.get("/api/trade/share-rules", q),
  listLedger: (q?: PageQ) => client.get("/api/trade/ledger", q),
  listSettlements: (q?: SettlementQ) => client.get("/api/trade/settlements", q),
  listWithdrawals: (q?: PageQ) => client.get("/api/trade/withdrawals", q),
  auditWithdrawal: (no, approve, rejectReason, auditorName) => client.post(`/api/trade/withdrawals/${no}/audit`, { approve, rejectReason, auditorName }),

  // S1 结算单闭环：生成/确认都是动作端点（状态迁移），详情明细挂在结算单资源下
  generateSettlements: (x) => client.post("/api/trade/settlements/generate", x),
  confirmSettlement: (no, operatorName) => client.post(`/api/trade/settlements/${no}/confirm`, { operatorName }),
  listSettlementRecords: (no, q?: PageQ) => client.get(`/api/trade/settlements/${no}/records`, q),

  // 财务扩展
  listShareRecords: (q?: ShareRecordQ) => client.get("/api/trade/share-records", q),
  listReconciles: (q?: PageQ) => client.get("/api/trade/reconciles", q),
  listInvoices: (q?: PageQ) => client.get("/api/trade/invoices", q),
  saveShareRule: (x) => client.post(x.ruleNo ? `/api/trade/share-rules/${x.ruleNo}` : "/api/trade/share-rules", x),
  saveInvoice: (x) => client.post(x.invoiceNo ? `/api/trade/invoices/${x.invoiceNo}` : "/api/trade/invoices", x),

  // 财务 B5：分润统计（trade 域聚合）/ 充值订单（钱包同主体，归 user 域）
  listShareSummaries: (q?: ShareSummaryQ) => client.get("/api/trade/share-summaries", q),
  listRechargeOrders: (q?: RechargeQ) => client.get("/api/user/recharge-orders", q),
};
