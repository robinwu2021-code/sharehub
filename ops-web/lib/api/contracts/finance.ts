// 覆盖范围：分账规则与流水、总账、结算、提现审批、对账、发票、分润统计、充值订单。
import type { PageQ, ShareSummaryQ, RechargeQ, SettlementQ, ShareRecordQ } from "../query";
import type {
  PageResult, ShareRule, LedgerEntry, Settlement, SettlementDraft, Withdrawal,
  ShareRecord, Reconcile, Invoice, ShareSummary, RechargeOrder,
} from "../../types";

export interface FinanceApi {
  listShareRules(q?: PageQ): Promise<PageResult<ShareRule>>;
  listLedger(q?: PageQ): Promise<PageResult<LedgerEntry>>;
  listSettlements(q?: SettlementQ): Promise<PageResult<Settlement>>;
  listWithdrawals(q?: PageQ): Promise<PageResult<Withdrawal>>;
  /** 提现审批：驳回必须带原因；auditorName 取当前登录用户（后端以会话为准，前端透传便于 mock）。 */
  auditWithdrawal(withdrawNo: string, approve: boolean, rejectReason?: string, auditorName?: string): Promise<Withdrawal>;

  // === S1 结算单闭环（权限码 finance:settlement:generate / :confirm）===
  /**
   * 生成结算单：金额从该周期的**分润明细汇总**而来，一次可为多个对象出单。
   * 幂等：同 对象+周期 已有结算单则整批拒绝（后端同样以此为准，前端不做去重兜底）。
   */
  generateSettlements(x: SettlementDraft): Promise<Settlement[]>;
  /** 确认结算：DRAFT → CONFIRMED，记确认人/时间。非法状态迁移由服务端拒绝。 */
  confirmSettlement(settleNo: string, operatorName?: string): Promise<Settlement>;
  /** 结算单构成明细：这张单的钱是哪几笔分润凑出来的。 */
  listSettlementRecords(settleNo: string, q?: PageQ): Promise<PageResult<ShareRecord>>;

  // === 财务扩展 tab ===
  listShareRecords(q?: ShareRecordQ): Promise<PageResult<ShareRecord>>;
  listReconciles(q?: PageQ): Promise<PageResult<Reconcile>>;
  listInvoices(q?: PageQ): Promise<PageResult<Invoice>>;
  saveShareRule(x: Partial<ShareRule> & { ruleNo?: string }): Promise<ShareRule>;
  saveInvoice(x: Partial<Invoice> & { invoiceNo?: string }): Promise<Invoice>;

  // === 财务域 B5：分润统计 / 充值订单（规格 §5 §6，均为只读）===
  /** 分润统计：dimension 是维度切换器的参数——一张表两种主体，不是两个接口。 */
  listShareSummaries(q?: ShareSummaryQ): Promise<PageResult<ShareSummary>>;
  listRechargeOrders(q?: RechargeQ): Promise<PageResult<RechargeOrder>>;
}
