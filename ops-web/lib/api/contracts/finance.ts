// 覆盖范围：分账规则与流水、总账、结算、提现审批、对账、发票、分润统计、充值订单。
import type { PageQ, ShareRuleQ, ShareSummaryQ, RechargeQ, SettlementQ, ShareRecordQ, ReconQ, InvoiceQ , ReportQ } from "../query";
import type {
  PageResult, ShareRule, LedgerEntry, Settlement, SettlementDraft, Withdrawal,
  ShareRecord, Reconcile, ReconAction, ReconDiff, ReconStats, Invoice, ShareSummary, RechargeOrder,

  VoucherDetail,
  VoucherCreatePayload,} from "../../types";

export interface FinanceApi {
  /** 分润规则：`dimension` 是双向视图的视角参数——一份规则按分成主体分开看，不是两套规则。 */
  listShareRules(q?: ShareRuleQ): Promise<PageResult<ShareRule>>;
  /** 账务分录。period 复用报表域 ReportQ（同一套周期口径）。 */
  listLedger(q?: ReportQ): Promise<PageResult<LedgerEntry>>;
  /**
   * 凭证下钻：同一 voucherNo 的全部分录 + 借贷合计与平衡判定。
   * **刻意只做只读**：手工记账是会计操作，开口子前必须先定「谁能记、能不能改已过账凭证、
   * 如何强制借贷平衡与留痕」，否则一个不校验平衡的「手工记账」比不做更危险。
   */
  getVoucher(voucherNo: string): Promise<VoucherDetail>;
  /**
   * 手工记账（补一张凭证）。**借贷必须相等**，服务端强制，页面绕不过去。
   * 只新增不修改：已过账凭证要更正就再记一张反向凭证 —— 直接改历史分录会让账实相符无从追溯。
   */
  createVoucher(x: VoucherCreatePayload): Promise<LedgerEntry[]>;
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
  listReconciles(q?: ReconQ): Promise<PageResult<Reconcile>>;
  listInvoices(q?: InvoiceQ): Promise<PageResult<Invoice>>;
  saveShareRule(x: Partial<ShareRule> & { ruleNo?: string }): Promise<ShareRule>;
  /** 登记/编辑发票草稿。开具后（ISSUED/VOID）抬头与金额由服务端拒绝修改。 */
  saveInvoice(x: Partial<Invoice> & { invoiceNo?: string }): Promise<Invoice>;

  // === S2 对账差错处理（权限码 finance:recon:handle）===
  /**
   * 批次下的差错明细（权限码 finance:recon:read）。批次行只说「差了多少」，
   * 差在哪几笔要看这张表——也是逐条处置（`handleRecon` 的 `diffId`）唯一的取号来源。
   */
  listReconDiffs(batchNo: string): Promise<ReconDiff[]>;
  /**
   * 差错处置：verify=已核对无误 / platform=平台侧 / channel=渠道侧（挂起待回执）/ compensate=发起补差。
   * `handleNote`（结论）必填；非法状态迁移、已平账批次由服务端拒绝。
   *
   * `diffId` 指定处置**单条**差错；不传 = 处置该批次全部未处置差错（与后端 resolve 同口径）。
   */
  handleRecon(batchNo: string, action: ReconAction, handleNote: string, operatorName?: string, diffId?: number): Promise<Reconcile>;
  /** 对账汇总条：未结差错笔数/金额与列表同源，处置一笔当场变（全量口径，不随列表筛选）。 */
  getReconStats(): Promise<ReconStats>;

  // === S2 发票开具 / 作废（权限码 finance:invoice:issue / finance:invoice:void）===
  /** 开具：DRAFT → ISSUED，服务端生成发票代码/号码并留痕；金额与来源结算单对不上则拒绝。 */
  issueInvoice(invoiceNo: string, operatorName?: string): Promise<Invoice>;
  /** 作废：ISSUED → VOID，`voidReason` 必填（不可逆）。草稿不可作废——改错直接编辑。 */
  voidInvoice(invoiceNo: string, voidReason: string, operatorName?: string): Promise<Invoice>;

  // === 财务域 B5：分润统计 / 充值订单（规格 §5 §6，均为只读）===
  /** 分润统计：dimension 是维度切换器的参数——一张表两种主体，不是两个接口。 */
  listShareSummaries(q?: ShareSummaryQ): Promise<PageResult<ShareSummary>>;
  listRechargeOrders(q?: RechargeQ): Promise<PageResult<RechargeOrder>>;
}
