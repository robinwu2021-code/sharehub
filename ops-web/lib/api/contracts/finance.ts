// 覆盖范围：分账规则与流水、总账、结算、提现审批、对账、发票、分润统计、充值订单。
import type { PageQ, ShareRuleQ, ShareSummaryQ, RechargeQ, SettlementQ, ShareRecordQ, ReconQ, InvoiceQ , ReportQ, PayoutAccountQ, WithdrawalQ, AdjustmentQ } from "../query";
import type {
  PageResult, ShareRule, LedgerEntry, Settlement, SettlementDraft, Withdrawal,
  ShareRecord, Reconcile, ReconAction, ReconDiff, ReconStats, Invoice, InvoiceView, ShareSummary, RechargeOrder,

  VoucherDetail,
  VoucherCreatePayload, PayoutAccount, PayReceiptPayload, WithdrawApplyPayload,
  SettlementView, Statement, StatementLang, Adjustment, AdjustmentConfirmPayload,
} from "../../types";

export interface FinanceApi {
  // === 收款账户（B3）===
  /**
   * 收款账户列表。代理端也持 `finance:payout_account:read` ——
   * 代理门户要显示「你还不能收款」，判据就是这里有没有可用账户。
   */
  listPayoutAccounts(q?: PayoutAccountQ): Promise<PageResult<PayoutAccount>>;
  /** 新增 / 修改。写权限与提现审核**分开发码**：合用的话「能审批」顺带变成「能改收款账号」。 */
  savePayoutAccount(x: {
    accountNo?: string; payeeType: "AGENT" | "VENUE"; payeeNo: string;
    bankCode: string; accountName: string; accountMasked: string;
    currency?: string; makeDefault?: boolean;
  }): Promise<PayoutAccount>;
  /** 停用。不做物理删除 —— 历史提现单要能回溯到当时打给了哪条账户记录。 */
  disablePayoutAccount(accountNo: string): Promise<PayoutAccount>;

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
  listWithdrawals(q?: WithdrawalQ): Promise<PageResult<Withdrawal>>;
  /** 提现审批：驳回必须带原因；auditorName 取当前登录用户（后端以会话为准，前端透传便于 mock）。 */
  auditWithdrawal(withdrawNo: string, approve: boolean, rejectReason?: string, auditorName?: string): Promise<Withdrawal>;
  /**
   * 打款回执登记：`PAYING` → `PAID` / `FAILED`（⑮）。
   *
   * 在此之前状态机的 `PAY`/`FAIL` 迁移**没有任何入口调用** —— 审批完的单子
   * 永远停在「出款在途」：钱算得清、批得了，批完不会动。
   * 成功必填渠道流水号，失败必填原因，判据见 `payReceiptError`。
   */
  payWithdrawal(withdrawNo: string, body: PayReceiptPayload): Promise<Withdrawal>;
  /**
   * 提现申请。由**代理商/场地方本人**发起（api/README §六·A：运营端没有也不该有创建入口）。
   *
   * 手续费 / 状态 / 申请人三者一律服务端定，入参里没有这些字段 ——
   * 让前端传 fee 的话，改一行请求体就能少交手续费。
   */
  applyWithdrawal(body: WithdrawApplyPayload): Promise<Withdrawal>;

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
  /** 结算单详情（深链 `?no=` 用）：本体 + 构成行。权限码 `finance:settlement:read`。 */
  getSettlement(settleNo: string): Promise<SettlementView>;
  /** 场地方对账单（结构化）：订单汇总 + 按合同 × 比例的分成 + 调整项 → 本期应付。 */
  getSettlementStatement(settleNo: string): Promise<Statement>;
  /**
   * 可打印对账单（整页 HTML，**不走统一信封**）。要带登录令牌，所以不能用普通链接打开 ——
   * 取回 HTML 后由页面开 blob 窗口，用户在浏览器里「打印 → 存为 PDF」。
   */
  getSettlementStatementHtml(settleNo: string, lang: StatementLang): Promise<string>;

  // === 结算调整项（C9 · G2）：撤场押金 / 进场费结清 / 保底补差，并入下一次出账 ===
  listSettlementAdjustments(q?: AdjustmentQ): Promise<PageResult<Adjustment>>;
  /** 确认（PENDING → CONFIRMED）。金额可改，**改了必须写 note**。权限码 `finance:settlement:confirm`。 */
  confirmSettlementAdjustment(adjNo: string, body: AdjustmentConfirmPayload): Promise<Adjustment>;
  /** 作废（PENDING / CONFIRMED → VOID），原因必填；已并入结算单的不能作废。 */
  voidSettlementAdjustment(adjNo: string, reason: string): Promise<Adjustment>;

  // === 财务扩展 tab ===
  listShareRecords(q?: ShareRecordQ): Promise<PageResult<ShareRecord>>;
  listReconciles(q?: ReconQ): Promise<PageResult<Reconcile>>;
  listInvoices(q?: InvoiceQ): Promise<PageResult<Invoice>>;
  /** 发票详情：发票 + 它由哪几笔订单开出来。列表里没有订单号清单。 */
  getInvoice(invoiceNo: string): Promise<InvoiceView>;
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
