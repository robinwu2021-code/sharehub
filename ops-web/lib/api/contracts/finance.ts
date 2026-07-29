// 覆盖范围：分账规则与流水、总账、结算、提现审批、对账、发票、分润统计、充值订单。
import type { PageQ, ShareSummaryQ, RechargeQ } from "../query";
import type {
  PageResult, ShareRule, LedgerEntry, Settlement, Withdrawal,
  ShareRecord, Reconcile, Invoice, ShareSummary, RechargeOrder,
} from "../../types";

export interface FinanceApi {
  listShareRules(q?: PageQ): Promise<PageResult<ShareRule>>;
  listLedger(q?: PageQ): Promise<PageResult<LedgerEntry>>;
  listSettlements(q?: PageQ): Promise<PageResult<Settlement>>;
  listWithdrawals(q?: PageQ): Promise<PageResult<Withdrawal>>;
  /** 提现审批：驳回必须带原因；auditorName 取当前登录用户（后端以会话为准，前端透传便于 mock）。 */
  auditWithdrawal(withdrawNo: string, approve: boolean, rejectReason?: string, auditorName?: string): Promise<Withdrawal>;

  // === 财务扩展 tab ===
  listShareRecords(q?: PageQ): Promise<PageResult<ShareRecord>>;
  listReconciles(q?: PageQ): Promise<PageResult<Reconcile>>;
  listInvoices(q?: PageQ): Promise<PageResult<Invoice>>;
  saveShareRule(x: Partial<ShareRule> & { ruleNo?: string }): Promise<ShareRule>;
  saveInvoice(x: Partial<Invoice> & { invoiceNo?: string }): Promise<Invoice>;

  // === 财务域 B5：分润统计 / 充值订单（规格 §5 §6，均为只读）===
  /** 分润统计：dimension 是维度切换器的参数——一张表两种主体，不是两个接口。 */
  listShareSummaries(q?: ShareSummaryQ): Promise<PageResult<ShareSummary>>;
  listRechargeOrders(q?: RechargeQ): Promise<PageResult<RechargeOrder>>;
}
