import type { AuditTrail } from "./common";
// 覆盖范围：财务域（trade）——分润规则/明细/统计、结算、提现、账务分录、
// 对账、发票、充值订单。

/**
 * 分成依据（ADR-027 §四 / V53）：这笔钱凭什么分。
 *
 * 一个伙伴在一个站点上可以既出资又运维，两份钱的比例与去向都不同。
 * 空串 = 不适用（VENUE 维度，维度本身即依据），也用于 V53 之前配的老规则。
 */
/**
 * 提现单状态。与后端 `WithdrawalStatus` **必须一字不差** ——
 * 对不上的症状是「筛了什么都没有」而不是报错（后端 StatusVocabularyAcrossEndsTest 盯着）。
 */
export type WithdrawalStatus = "APPLY" | "AUDIT" | "PAYING" | "PAID" | "FAILED";

/**
 * 提现单上的运营动作。见 {@link WITHDRAW_TRANSITIONS}：
 * `approve`/`reject` 共用审批端点，`pay`/`payFail` 共用回执端点。
 */
export type WithdrawAction = "approve" | "reject" | "pay" | "payFail";

/**
 * 钱包充值单状态。与后端 `RechargeOrderStatus` 必须一字不差。
 *
 * ⚠️ 与支付单（`pay_order`）不是同一套：那边初始态是 `INIT`、还有 `PAYING`/`CLOSED`。
 * 两者都有 `PAID`/`FAILED` —— 相像但不相同，合并过一次就会变成「充值单永远查不到」。
 */
export type RechargeOrderStatus = "PENDING" | "PAID" | "FAILED" | "REFUNDED";

export const SHARE_BASES = ["INVEST", "DEVELOP", "OPERATE", "REFER"] as const;
export type ShareBasis = (typeof SHARE_BASES)[number];

/**
 * 分账模式。**本平台是「全支付 + 双分账」**，两条路子结算口径完全不同：
 * - `CHANNEL_SPLIT` 支付渠道直接分账，钱在收单时就分走了；
 * - `LEDGER` 先全额入账、按台账结算，钱还在平台账上。
 * 界面上看不出走的哪条时，对账对不上也说不清该找渠道还是找自己。
 */
export type ShareMode = "CHANNEL_SPLIT" | "LEDGER";

export interface ShareRule {
  ruleNo: string;
  /**
   * 阶梯 / 复合规则表达式（JSON 文本）；简单比例规则为空。
   *
   * **不为空时 `rate` 不是全部真相** —— 界面只显示 rate 会把一条阶梯规则
   * 呈现成一个固定比例，而实际分多少取决于落在哪一档。
   */
  formula: string | null;
  dimension: "VENUE" | "AGENT";
  /**
   * 分成方**业务号**（VEN3xx / AG00x）—— 取价就是按它匹配的。
   *
   * 缺了它的规则在分账时一条都命中不了：`ShareGenerator` 按 `payee_no` 精确查，
   * 而 `payee_no` 为空就永远匹配不上。**界面上看着配好了，钱却分不出去，且不报错。**
   * 所以分成方必须是「选」出来的，不能手打名字（同合同「按编号连」的理由）。
   */
  payeeNo: string;
  payeeName: string;
  /** 见 SHARE_BASES。留空 = 该分成方的通用规则（任何依据都能回落到它）。 */
  basis?: ShareBasis | "";
  /** 币种。多市场下只给比例不给币种，结算时不知道按哪个币算固定额。 */
  currency?: string | null;
  mode: ShareMode;
  rate: number; // 0..1
  priority: number;
}
// —— 结算单（trade 域 · S1）——
// 状态语义：GEN=已生成待确认（金额可重算/可作废）→ CONFIRMED=财务确认（进入应付）→ PAID=已打款。
//
// ⚠️ 这里曾经写作 `DRAFT`，理由是「DRAFT 是草稿态的通行叫法（发票用的就是 DRAFT）」。
// 那个理由本身没错，错在**只改了一侧**：DDL 列注释、后端 SettlementStatus 枚举、
// 库里的存量数据都仍是 `GEN`。于是 GEN 状态的结算单在运营端是个未知值 ——
// 徽标映射不上、按「待确认」筛一条都查不到，而两边都不报错。
//
// 「叫法更通行」是**标签**的事，不是**值**的事：下面 STL_STATUS 里它显示为「待确认」，
// 用户从来看不到 GEN 这三个字母。值以 DDL 为准，标签随便挑好听的。
export type SettlementStatus = "GEN" | "CONFIRMED" | "PAID";
export type SettlementAction = "confirm" | "pay";

export interface Settlement {
  settleNo: string;
  payeeType: "VENUE" | "AGENT";
  /** 结算对象业务号：场地方 VEN3xx / 代理商 AG00x。汇总分润明细的连接键之一。 */
  payeeNo: string;
  payeeName: string;
  period: string; // 结算周期 `2026-07`
  /** 金额 = 该 (对象, 周期) 下**分润明细金额之和**，不独立造数（详情抽屉可逐笔核对）。 */
  totalAmount: number;
  /** 构成本单的分润明细笔数（0 笔不允许出单）。 */
  recordCount: number;
  currency: string;
  status: SettlementStatus;
  createdAt: string;
  confirmedBy: string | null;
  confirmedAt: string | null;
}

/** 生成结算单入参：一次可为多个对象出同周期的单（后端一个事务）。 */
export interface SettlementDraft {
  payeeType: "VENUE" | "AGENT";
  payeeNos: string[];
  period: string; // `2026-07`
  // 没有 operatorName：后端 SettlementGenerateReq 只认 period/payeeType/payeeNos，
  // 出账人由会话决定。payeeNos 从 2026-09-26 起才真的被后端读 ——
  // 此前勾了三个场地方，出的是该类型全部收款方的账。
}

/**
 * 结算单状态机（SSOT）：页面按钮可用性与 mock/后端校验共用同一份，
 * 与工单域 `WO_TRANSITIONS` 同一套写法。
 * `pay`（打款）暂未开放动作入口，但合法迁移必须在这里声明清楚，否则种子里的 PAID 无从解释。
 */
export const STL_TRANSITIONS: Record<SettlementAction, { from: SettlementStatus[]; to: SettlementStatus; label: string }> = {
  confirm: { from: ["GEN"], to: "CONFIRMED", label: "确认结算" },
  pay: { from: ["CONFIRMED"], to: "PAID", label: "打款" },
};
export const canSettlementTransition = (from: SettlementStatus, action: SettlementAction) =>
  STL_TRANSITIONS[action].from.includes(from);
export interface Withdrawal extends AuditTrail {
  withdrawNo: string;
  // 收款主体的**号**，不只是名字：代理端只能看自己的单子，按名字筛会把同名主体混进来。
  // 与 settlements / shareRecords 用同一套 (payeeType, payeeNo) 口径，否则对不上账。
  payeeType: "VENUE" | "AGENT";
  payeeNo: string;
  payeeName: string;
  amount: number;
  currency: string;
  status: WithdrawalStatus;
  appliedAt: string;
  // —— 资金审批合规四件套（对标补齐）——
  fee: number; // 提现手续费（与 amount 同币种，实际到账 = amount - fee）
  // —— 申请时的收款账户**快照**（后端一直在返回，前端此前没声明 → 界面看不到）——
  //
  // 为什么必须是快照而不是现查：审批页此前只能查「这个收款方**当前**有没有默认账户」，
  // 若申请之后对方改了默认账户，界面显示的是新账户、而单子是按旧账户报的 ——
  // 审批人核对的对象与实际打款对象不是同一个，且不报错。
  // 同 ADR「数据范围锚点 = 下单时快照」的口径。
  accountNo: string | null;   // → PayoutAccount.accountNo
  bankCode: string | null;    // 冗余一份：账户被停用/改名后仍要能回溯当时打给了哪家行
  // 申请人编号。审计链此前只有 auditorName（谁批），缺「谁报的」这一半。
  applicantNo: string | null;
  // ⚠️ 后端还返回 netAmount，**故意不接**：未审批的单子要按**现行**费率实时算
  //（见下方 withdrawNetOf 的说明），落库的 netAmount 是申请时的旧数。
  //  接上它等于给审批人两个都叫「实际到账」的数，而点「通过」时扣的是另一个。
  // —— 打款回执（⑮，V57）。批准之后钱到底出去没有，此前无处可答 ——
  payChannel: "NEARPAY" | "MANUAL" | null;
  payRef: string | null;   // 渠道流水号：银行回单号 / nearpay 打款单号
  payerName: string | null; // 登记回执的人。与 auditorName 分开，才查得出是不是同一个人放的款
  failReason: string | null; // 打款失败原因。**不是** rejectReason（那是审批驳回）
  paidAt: string | null;
}

/**
 * 提现申请入参。
 *
 * **没有 fee 字段**：手续费一律服务端按业务规则现算（口径 SSOT 见本文件
 * `computeWithdrawFee`）。让前端传费用的话，改一行请求体就能少交手续费。
 * 状态与申请人同理，都不在入参里。
 */
export interface WithdrawApplyPayload {
  payeeType: Withdrawal["payeeType"];
  payeeNo: string;
  payeeName: string;
  amount: number;
  currency: string;
}

/**
 * 申请金额的校验，返回错误文案；通过返回 null。
 *
 * 页面禁用按钮与 mock 校验共用这一份 —— 分开写的话会出现
 * 「按钮能点、点了报错」或更糟的「按钮不能点、其实是允许的」。
 */
export const withdrawApplyError = (amount: number, rule?: WithdrawFeeRule & { minAmount?: number }): string | null => {
  if (!(amount > 0)) return "提现金额必须大于 0";
  if (rule?.minAmount != null && amount < rule.minAmount) {
    return `低于最低提现额 ${rule.minAmount}`;
  }
  // 手续费吃掉全部本金的申请不该受理：批下去实际到账是 0 或负数
  if (rule && computeWithdrawFee(amount, rule) >= amount) return "手续费不得大于等于提现金额，请提高提现额度";
  return null;
};

/** 打款渠道。MANUAL = 人工转账后回填 —— nearpay 接通前这是唯一的真实路径。 */
export const PAY_CHANNELS = ["MANUAL", "NEARPAY"] as const;
export type PayChannel = (typeof PAY_CHANNELS)[number];

/**
 * 打款回执入参。`success` 决定落 PAID 还是 FAILED。
 *
 * 成功必填 `payRef`、失败必填 `failReason` —— 页面与 mock 共用
 * {@link payReceiptError} 这一份判据，不各写一遍。
 */
export interface PayReceiptPayload {
  success: boolean;
  channel: PayChannel;
  payRef?: string;
  failReason?: string;
}

/**
 * 提现单状态机（SSOT）：页面按钮可用性与 mock/后端校验共用同一份，
 * 与工单 `WO_TRANSITIONS`、结算 `STL_TRANSITIONS` 同一套写法。
 *
 * **一个动作一条边，即使两个动作共用一个端点** —— 打款端点后端是
 * `stateMachine.next(status, ok ? "PAY" : "FAIL")`：登记回执时填成功就
 * `PAYING→PAID`、填失败就 `PAYING→FAILED`。审批端点同理（approve/reject）。
 * 若按端点建表就得写成 `to: Status[]`，那样「这一步走到哪」在表里就答不出来了，
 * 而这正是状态机该回答的唯一问题。
 * **动作是 UI 概念、端点是传输概念，不必一一对应。**
 *
 * `APPLY→AUDIT`（后端 SUBMIT 边）**不在此表**：那是代理侧提交申请，
 * 运营端没有、也不该有这个动作。
 */
export const WITHDRAW_TRANSITIONS: Record<WithdrawAction,
  { from: readonly WithdrawalStatus[]; to: WithdrawalStatus; label: string }> = {
  approve: { from: ["APPLY", "AUDIT"], to: "PAYING", label: "审核通过" },
  reject: { from: ["APPLY", "AUDIT"], to: "FAILED", label: "驳回" },
  pay: { from: ["PAYING"], to: "PAID", label: "登记打款成功" },
  payFail: { from: ["PAYING"], to: "FAILED", label: "登记打款失败" },
};

export const canWithdrawAction = (status: WithdrawalStatus, action: WithdrawAction) =>
  WITHDRAW_TRANSITIONS[action].from.includes(status);

/**
 * 审批入口是否可用（通过/驳回共用一个端点与一个弹窗，弹窗里再选）。
 *
 * 此前页面手写 `(w.status === "AUDIT" || w.status === "APPLY")` —— 正是
 * `WithdrawalStateMachine` 的 APPROVE from 集抄了一份。抄一份不是风格问题：
 * 后端加一条边时，手抄处没有任何东西会提醒。
 */
export const canAuditWithdrawal = (status: WithdrawalStatus) =>
  canWithdrawAction(status, "approve") || canWithdrawAction(status, "reject");

/**
 * 只有「出款在途」的单子能登记回执：APPLY/AUDIT 还没批，PAID/FAILED 已是终态。
 * 结论与此前手写的 `status === "PAYING"` 相同，但现在它是**从表里读出来的**。
 */
export const canPayWithdrawal = (status: Withdrawal["status"]) =>
  canWithdrawAction(status, "pay") || canWithdrawAction(status, "payFail");

/**
 * 回执入参校验，返回错误文案；通过返回 null。
 *
 * 放在类型层而不是各自实现：按钮禁用、mock 校验、将来的后端错误回显要说同一句话，
 * 否则「界面说能提交、提交回来说不行」。
 */
export const payReceiptError = (p: PayReceiptPayload): string | null => {
  if (p.success && !p.payRef?.trim()) {
    // 「已到账」要能在对账时被证实。没有流水号就只剩一句人说的话
    return "登记到账必须填写渠道流水号（银行回单号 / nearpay 打款单号）";
  }
  if (!p.success && !p.failReason?.trim()) return "登记打款失败必须填写失败原因";
  return null;
};

// —— 提现手续费口径（SSOT，S7）——
// 费率与封顶只有一处来源：系统设置 · 业务规则 `BizRules.withdraw`（GET /api/platform/biz-rules）。
// 后端 `SysBizRule` 的注释是同一句要求：`fin_withdrawal.fee` 必须取自 `feeRate`/`feeCap`，
// 财务侧不得另存一份阈值 —— 两处口径一旦分叉，对账永远差钱。
// 因此这里只放**纯函数**：页面表格、审批抽屉、CSV 导出与 mock 种子全调它，不各算一遍。
/** 只取算费用得上的两个字段（不依赖整个 WithdrawRule，mock 种子与测试都好构造）。 */
export interface WithdrawFeeRule { feeRate: number; feeCap: number }
/** 手续费 = min(金额 × 费率, 封顶)，两位小数。**没有下限**——业务规则里没有这个字段，就不许凭空加。 */
export const computeWithdrawFee = (amount: number, rule: WithdrawFeeRule): number =>
  Number(Math.min(amount * rule.feeRate, rule.feeCap).toFixed(2));
/** 费率变更只影响还没批的单子：这几个状态是「钱还没出去」。 */
export const WITHDRAW_FEE_PENDING: Withdrawal["status"][] = ["APPLY", "AUDIT"];
/**
 * 展示用手续费。未审批的按**现行**规则实时算 —— 审核页给的必须是点「通过」那一刻真会扣的数；
 * 已审批（PAYING/PAID/FAILED）一律按落库值 —— 事后调费率不能改写历史放款额。
 * `rule` 取不到时（业务规则未加载 / 无 `system:biz_rule:read`）退回落库值，不猜一个费率出来。
 */
export const withdrawFeeOf = (w: Withdrawal, rule?: WithdrawFeeRule): number =>
  rule && WITHDRAW_FEE_PENDING.includes(w.status) ? computeWithdrawFee(w.amount, rule) : w.fee;
/** 实际到账：必须和手续费同源，否则表上「金额 − 手续费 ≠ 实发」这种数会直接骗到审批人。 */
export const withdrawNetOf = (w: Withdrawal, rule?: WithdrawFeeRule): number =>
  Number((w.amount - withdrawFeeOf(w, rule)).toFixed(2));

// —— 账务分录（复式记账，trade 域）——
/**
 * 手工记账入参。凭证号由服务端派（让前端选号必然撞号），故不在入参里。
 * `entries` 至少两条且借贷两侧都要有；金额一律正数，借/贷由 `direction` 表达。
 */
export interface VoucherCreatePayload {
  summary: string;
  orderNo?: string | null;
  currency?: string;
  entries: { account: string; direction: LedgerEntry["direction"]; amount: number; summary?: string }[];
}

/**
 * 凭证详情：一张凭证的全部分录 + 借贷合计与平衡判定。
 * `balanced` 由服务端算而不是前端心算 —— 不平的凭证是记账错误，判定口径必须只有一处。
 */
export interface VoucherDetail {
  voucherNo: string;
  entries: LedgerEntry[];
  debit: number;
  credit: number;
  balanced: boolean;
}

export interface LedgerEntry {
  entryNo: string;
  voucherNo: string;
  orderNo: string | null;
  account: string; // 账户：现金/应付场地方/应付代理/平台收入…
  /** 科目编号。`account` 是人话名称，连表/导出要用编号。 */
  accountNo: string | null;
  /** 业务来源类型 + 单号：追一笔分录「这是哪来的」就靠这两个。 */
  bizType: string | null;
  bizNo: string | null;
  direction: "DEBIT" | "CREDIT"; // 借/贷
  amount: number;
  currency: string;
  summary: string;
  createdAt: string;
}

// —— 财务 · 待建功能补全（trade 域）——
/** 与后端 `ShareRecordStatus` 枚举同名同值。**具名不是风格** —— 两端同名词表比对只认
 *  具名 `export type`，内联在 interface 里的联合它一个都发现不了。 */
export type ShareRecordStatus = "PENDING" | "DONE";
export interface ShareRecord {
  recordNo: string;
  mode: ShareMode;
  orderNo: string;
  dimension: "VENUE" | "AGENT";
  /** 分成方业务号（VEN3xx / AG00x）。结算单按 (dimension, payeeNo, period) 汇总本表。 */
  payeeNo: string;
  payeeName: string;
  /**
   * 分成依据（V53）。一单里同一个伙伴可以有出资 + 运维两条，**靠这一列区分** ——
   * 没有它，分润明细里会出现几条看起来一模一样的行，而金额不同。
   */
  basis?: ShareBasis | "";
  /** 分润基数（GMV 快照）。有它才能在页面上验「基数 × 比例 = 金额」。 */
  grossAmount: number;
  /** PENDING=待结算 / DONE=已结算。财务对账第一个问的就是这笔结没结。 */
  status: ShareRecordStatus;
  /** 已结算时归属的结算单号；未结为 null。 */
  settleNo: string | null;
  amount: number;
  rate: number; // 0..1
  currency: string;
  /** 归属结算周期 `2026-07`（= createdAt 所在月，冗余一列便于按周期汇总与筛选）。 */
  period: string;
  createdAt: string;
}
// —— 对账 · 差错处理（trade 域 · S2）——
// `status` 是**比对结果**（跑批算出来的，人改不了）；`handleStatus` 是**处置进度**（人推动的）。
// 两者必须分开：把「已核对无误」写回 status=MATCHED 会篡改跑批事实，日后无从审计。
/** 差错处置进度：OPEN=待处理 → HANDLING=处理中（挂起等外部回执）→ RESOLVED/IGNORED（终态）。 */
export type ReconHandleStatus = "OPEN" | "HANDLING" | "RESOLVED" | "IGNORED";
/** 处置动作（= 页面上的四个按钮，也是 mock/后端校验的入口参数）。 */
export type ReconAction = "verify" | "platform" | "channel" | "compensate";
/** 处置结论分类：终态/中间态都要留下「判成了哪一类差错」，否则复盘时只剩一段自由文本。 */
export type ReconHandleResult = "VERIFIED_OK" | "PLATFORM_ERROR" | "CHANNEL_ERROR" | "COMPENSATED";

/**
 * ReconTaskStatus
 *
 * <p>抽成**具名** `export type` 而不是内联在 interface 里：跨端词表卡口
 * `StatusVocabularyAcrossEndsTest` 是「两端同名即比对」——
 * 内联的联合类型它**配不上对，一个字都比不了**。后端同名枚举 `ReconTaskStatus` 取值一致。
 */
export type ReconTaskStatus = "MATCHED" | "DIFF";

export interface Reconcile {
  batchNo: string;
  period: string;
  /** 账单日与渠道 —— 对账批次的身份。没有它，列表里几行长得一模一样。 */
  billDate: string | null;
  channel: string | null;
  nearpayTotal: number;
  ledgerTotal: number;
  /** 差额 = nearpayTotal − ledgerTotal。>0 渠道多、账务少记；<0 账务多记、渠道少到账。 */
  diff: number;
  currency: string;
  /** 跑批比对结果（只读事实，不因人工处置而变）。 */
  status: ReconTaskStatus;
  createdAt: string;
  /** 处置进度；已平批次为 null（无差错可处理）。 */
  handleStatus: ReconHandleStatus | null;
  handleResult: ReconHandleResult | null;
  /** 处理结论（必填）：写清依据——金额、凭证号、对接人。 */
  handleNote: string | null;
  handledBy: string | null;
  handledAt: string | null;
}

/** 差错类型：渠道单边 / 我方单边 / 两侧金额不等 / 两侧状态不一致（口径同后端 recon_diff.diff_type）。 */
export type ReconDiffType = "ONLY_IN_NEARPAY" | "ONLY_IN_LEDGER" | "AMOUNT_MISMATCH" | "STATUS_MISMATCH";

/**
 * 批次下的**单条**差错（子表 recon_diff，无独立业务键，只有自增 id）。
 *
 * 批次那一行只给「差了多少钱」，逐笔差在哪必须看这张表——否则处置只能对整批下手，
 * 而后端的 resolve 本来就收 `diffId`（不带才是整批）。
 */
export interface ReconDiff {
  id: number;
  batchNo: string;
  /** 支付单号（pay_order.pay_no）；渠道单边差错时也是渠道流水的落点。 */
  payNo: string;
  diffType: ReconDiffType;
  /** 差错上下文（两侧原始金额/状态）的 JSON 文本，后端原样透传，前端只解析不改写。 */
  detail: string;
  /** 已平账标记：处置只翻这个标记，绝不回头改历史分录（分录是只增表，纠错要走红冲）。 */
  resolved: boolean;
  /**
   * 逐笔处置留痕。
   *
   * 批次那一份（`Reconcile.handleResult/handleNote/handledBy`）**会被下一次处置覆盖** ——
   * 逐笔处置时把 A 判成 verify、再把 B 判成 channel，批次上只剩 channel。
   * 这四个字段才答得出「这一笔是谁、以什么结论、什么时候处置的」。
   * `handledBy` 由服务端按会话回填，前端不传（审计事实不能由调用方提供）。
   */
  handleResult?: string | null;
  handleNote?: string | null;
  handledBy?: string | null;
  handledAt?: string | null;
}

/** `ReconDiff.detail` 解析后的形状（两侧金额与一句人读的上下文）。 */
export interface ReconDiffDetail {
  nearpay?: number;
  ledger?: number;
  note?: string;
}
/**
 * 解析 detail。**解析失败返回 null 而不抛**：detail 是后端存的自由 JSON 文本，
 * 哪天格式变了也只该让这一列退化成原文显示，不能把整个抽屉炸掉。
 */
export function parseReconDiffDetail(detail: string | null | undefined): ReconDiffDetail | null {
  if (!detail) return null;
  try {
    const v = JSON.parse(detail) as unknown;
    return v && typeof v === "object" && !Array.isArray(v) ? (v as ReconDiffDetail) : null;
  } catch {
    return null;
  }
}

/**
 * 对账差错状态机（SSOT）：页面按钮可用性与 mock/后端校验共用同一份，
 * 与结算单 `STL_TRANSITIONS`、工单 `WO_TRANSITIONS` 同一套写法。
 *
 * 四个动作覆盖现实里差错的四种去向，都必须落到 `result` 上：
 * 核对无误 → 忽略结案；平台/渠道定责 → 转处理中（各自等内部补记账 / 外部回执）；补差 → 结案。
 */
export const RECON_TRANSITIONS: Record<ReconAction, {
  from: ReconHandleStatus[]; to: ReconHandleStatus; result: ReconHandleResult; label: string; hint: string;
}> = {
  verify: {
    from: ["OPEN", "HANDLING"], to: "IGNORED", result: "VERIFIED_OK",
    label: "已核对无误（忽略）", hint: "逐笔核对后确认账实相符（如跨日切分次日自动冲平），差错结案不追款",
  },
  platform: {
    from: ["OPEN"], to: "HANDLING", result: "PLATFORM_ERROR",
    label: "标记为平台侧差错", hint: "定责平台记账错漏，转处理中等待补记账凭证，补完再发起补差或核对结案",
  },
  channel: {
    from: ["OPEN"], to: "HANDLING", result: "CHANNEL_ERROR",
    label: "标记为渠道侧差错（挂起待渠道回执）", hint: "定责支付渠道，已提差错工单，挂起等 nearpay 回执，回执到后再结案",
  },
  compensate: {
    from: ["OPEN", "HANDLING"], to: "RESOLVED", result: "COMPENSATED",
    label: "发起补差", hint: "按差额发起补差并结案——真金白银找平，结论里必须写清补差单号与金额",
  },
};
export const canReconTransition = (from: ReconHandleStatus, action: ReconAction) =>
  RECON_TRANSITIONS[action].from.includes(from);
/** 终态：不再出处理按钮。 */
export const RECON_TERMINAL: ReconHandleStatus[] = ["RESOLVED", "IGNORED"];

/**
 * 对账汇总条。**全量口径**（不随列表筛选变），但**随处理动作实时重算**——
 * 处理一笔差错，未结笔数/金额当场下降，与列表同源于 `reconciles` 数组。
 */
export interface ReconStats {
  batchCount: number; // 批次总数
  matchedCount: number; // 已平批次
  /** 未结差错笔数 = OPEN + HANDLING（页面上「差错笔数」就是它）。 */
  diffCount: number;
  /** 未结差错金额 = 未结案差错的 Σ|diff|（正负差错都是要找平的钱，取绝对值）。 */
  diffAmount: number;
  openCount: number;
  handlingCount: number;
  /** 已结案 = RESOLVED + IGNORED。 */
  closedCount: number;
  closedAmount: number;
  currency: string;
}

// —— 发票 · 开具 / 作废（trade 域 · S2）——
export type InvoiceStatus = "DRAFT" | "ISSUED" | "VOID";
export type InvoiceAction = "issue" | "void";

/**
 * 发票详情 = 发票 + **它是由哪几笔订单开出来的**。
 *
 * 后端 `GET /api/trade/invoices/{invoiceNo}`。税务或客户质疑金额时，
 * 第一个要答的就是这份订单号清单 —— 列表里没有它。
 */
export interface InvoiceView {
  invoice: Invoice;
  orderNos: string[];
}

export interface Invoice {
  invoiceNo: string;
  /**
   * 出票 PDF 地址，开票后由后端回填；未开票为 null。
   *
   * 后端一直在返回，前端没声明 —— 于是**开完票在界面上拿不到票**，
   * 要去库里捞。发票页此前连「下载 / 查看」入口都没有。
   */
  fileUrl: string | null;
  /** 收款方类型 + 编号。按**编号**连，名字只作展示（同合同/分润规则的理由）。 */
  payeeType: "VENUE" | "AGENT" | null;
  payeeNo: string | null;
  payeeName: string;
  amount: number;
  vatTrn: string;
  currency: string;
  status: InvoiceStatus;
  /** 来源单据类型：当前只对**已确认的结算单**开票（开具时校验金额必须一致）。 */
  sourceType: "SETTLEMENT";
  /** 来源单号（Settlement.settleNo）——发票金额的出处，详情里可核对。 */
  sourceNo: string;
  /** 税局发票代码 / 号码：开具时生成，草稿为空。 */
  invoiceCode: string | null;
  invoiceNumber: string | null;
  /** 草稿未开具 → null（原类型是 string 但草稿行里塞了个假时间，页面只好靠 status 遮住）。 */
  issuedAt: string | null;
  issuedBy: string | null;
  voidedAt: string | null;
  voidedBy: string | null;
  /** 作废原因（必填）：作废比开具更危险，不留原因等于账面凭空少一张票。 */
  voidReason: string | null;
}

/**
 * 发票状态机（SSOT）：DRAFT → ISSUED → VOID。
 * 作废**只能从 ISSUED 走**——草稿还没进账，改错直接编辑即可，不该占用一个作废号。
 */
export const INV_TRANSITIONS: Record<InvoiceAction, { from: InvoiceStatus[]; to: InvoiceStatus; label: string }> = {
  issue: { from: ["DRAFT"], to: "ISSUED", label: "开具" },
  void: { from: ["ISSUED"], to: "VOID", label: "作废" },
};
export const canInvoiceTransition = (from: InvoiceStatus, action: InvoiceAction) =>
  INV_TRANSITIONS[action].from.includes(from);
/** 抬头/金额是否还能改：只有草稿能改，开具后金额已进税务口径，作废后是历史。 */
export const canEditInvoiceFields = (status: InvoiceStatus) => status === "DRAFT";

// —— 分润统计（财务域 · 阶段 2，对标简电云「佣金统计」）——
// 竞品把佣金统计按「运营商 / 商户」切成两套菜单两张表；我们做**一张表 + 顶部维度切换器**，
// 同一套列、同一次查询，少一次跳转——这是「信息更清晰」的正例。
export interface ShareSummary {
  /** 主体维度：与 ShareRule / ShareRecord 的 dimension 同枚举，切换器切的就是它 */
  dimension: "VENUE" | "AGENT";
  payeeNo: string; // 场地方 VEN3xx / 代理商 AG00x
  payeeName: string;
  period: string; // 统计周期 `2026-07`
  orderCount: number;
  gmv: number; // 交易额
  shareAmount: number; // 分润额
  settledAmount: number; // 已结算（≤ 分润额）
  pendingAmount: number; // 待结算 = 分润额 − 已结算（列表高亮列：财务最关心的数）
  currency: string;
}

// —— 充值订单（财务域 · 阶段 3）——
// 归「用户账」分组，与钱包同主体。channelCode 取值必须与 系统设置·支付渠道 的 channelCode 对得上。
export interface RechargeOrder {
  rechargeNo: string;
  userNo: string; // U30xx
  nickname: string; // 冗余展示用：列表不必再跳用户页确认是谁
  packageNo: string | null; // 充值套餐；null = 自定义金额
  payAmount: number; // 实付
  giftAmount: number; // 赠送
  creditAmount: number; // 到账 = 实付 + 赠送
  currency: string;
  channelCode: string; // NEARPAY / STRIPE / TAP …（关联 PaymentChannel.channelCode）
  status: RechargeOrderStatus;
  createdAt: string; // 下单时间：PENDING/FAILED 无 paidAt，日期范围筛选一律以本字段为准
  paidAt: string | null;
  pspTxnNo: string | null; // 支付网关流水号；未支付为空
}

// —— 收款账户（B3）——

/**
 * 收款账户 —— 钱最终打到哪里。
 *
 * ⚠️ `payeeType` 是 `AGENT` 不是 `OPERATOR`：`share_record` 与 `stl_withdrawal`
 * 现网存的都是 AGENT，三张表要能对得上。改名是 ADR-029 §5.1 B 步的事，那时一起改。
 */
export interface PayoutAccount {
  accountNo: string;
  payeeType: "AGENT" | "VENUE";
  payeeNo: string;
  bankCode: string;
  accountName: string;
  /** 账号掩码。**只供显示** —— 同号段的掩码可能相同，不能拿它做任何等值判断。 */
  accountMasked: string;
  currency: string;
  isDefault: boolean;
  status: "ACTIVE" | "DISABLED";
}

// —— 结算单详情（后端 `FinDtos.SettlementView`：GET /api/trade/settlements/{settleNo}）——

/** 结算单构成行：这张单由哪些来源凑成（SHARE = 分润明细；调整项并入时 refType 为调整项）。 */
export interface SettlementDetail {
  refType: string;
  refNo: string;
  amount: number;
}
/**
 * 详情 = 结算单本体 + 构成行。
 * ⚠️ 详情里的 `settlement.recordCount` 后端实测为 null（列表才算），界面以 `details.length` 为准。
 */
export interface SettlementView {
  settlement: Settlement;
  details: SettlementDetail[];
}

// —— 结算调整项（运营核心流程 C9 · G2，后端 AdjustmentController）——

/**
 * 调整项状态。与后端 `AdjustmentStatus` 同名同值：
 * 系统出建议值（PENDING）→ 财务确认、可改金额（CONFIRMED）→ 下次出账并入结算单（SETTLED）；
 * 待确认与已确认都可作废（VOID），已并入结算单的不能再作废 —— 那张单的钱已经算进去了。
 */
export type AdjustmentStatus = "PENDING" | "CONFIRMED" | "SETTLED" | "VOID";
/** 调整项种类。与后端 `AdjustmentKind` 同名同值。 */
export type AdjustmentKind = "DEPOSIT_REFUND" | "ENTRY_FEE_SETTLE" | "GUARANTEE_TOPUP";
/**
 * 来源。后端是字符串常量（无枚举）：SITE_CLOSED = 撤场关闭按合同生成；GUARANTEE = 保底补差（直接已确认）。
 */
export type AdjustmentSource = "SITE_CLOSED" | "GUARANTEE";
export type AdjustmentAction = "confirm" | "void";

/**
 * 调整项迁移表（SSOT）：页面按钮与 mock 校验共用。
 * 与后端 `AdjustmentServiceImpl` 一致：confirm 只认 PENDING；void 认 PENDING / CONFIRMED（SETTLED 不可作废）。
 */
export const ADJUSTMENT_TRANSITIONS: Record<AdjustmentAction, { from: AdjustmentStatus[]; to: AdjustmentStatus; label: string }> = {
  confirm: { from: ["PENDING"], to: "CONFIRMED", label: "确认" },
  void: { from: ["PENDING", "CONFIRMED"], to: "VOID", label: "作废" },
};
export const canAdjustmentTransition = (s: AdjustmentStatus, a: AdjustmentAction) =>
  ADJUSTMENT_TRANSITIONS[a].from.includes(s);

/**
 * 后端 `AdjustmentService.Adjustment` 原样形状。
 *
 * 金额**带符号**：负数 = 场地方应返还平台（押金、进场费折算），正数 = 平台补给场地方（保底补差）。
 * ⚠️ 后端出参里**没有 `period`**（实体有、VO 漏了）—— 保底补差按账期生成，界面暂时看不到它属于哪个月。
 */
export interface Adjustment {
  adjNo: string;
  payeeType: Settlement["payeeType"];
  payeeNo: string;
  payeeName: string | null;
  kind: AdjustmentKind;
  /**
   * 按账期的调整（保底补差）填 `YYYY-MM`；一次性的（撤场结清）为空串。
   *
   * <p>保底补差按月生成、同一合同每月一笔、金额还可能一样 ——
   * 不带账期就分不清这笔是本月的还是上月重复生成的。
   */
  period: string;
  siteNo: string | null;
  contractNo: string | null;
  amount: number;
  /** 系统建议值。确认金额与它不同时必须写说明。 */
  suggestedAmount: number | null;
  currency: string;
  status: AdjustmentStatus;
  /** 并入的结算单号；未出账为 null。 */
  settleNo: string | null;
  source: AdjustmentSource;
  note: string | null;
  confirmedBy: string | null;
  confirmedAt: string | null;
  createdAt: string | null;
}
/** 确认入参：金额可改（不传 = 按当前金额），改了必须写 note。 */
export interface AdjustmentConfirmPayload {
  amount?: number;
  note?: string;
}

// —— 场地方对账单（运营核心流程 G3，后端 StatementService.Statement）——

export interface StatementShareLine {
  contractNo: string | null;
  rate: number;
  orders: number;
  gross: number;
  amount: number;
}
export interface StatementAdjustLine {
  adjNo: string;
  kind: AdjustmentKind;
  contractNo: string | null;
  siteNo: string | null;
  period: string | null;
  amount: number;
  note: string | null;
}
/** 一张结算单一份对账单：订单汇总 + 按合同 × 比例的分成 + 调整项，`total` = 本期应付。 */
export interface Statement {
  settleNo: string;
  payeeType: Settlement["payeeType"];
  payeeNo: string;
  payeeName: string;
  period: string;
  currency: string;
  status: SettlementStatus;
  orderCount: number;
  grossTotal: number;
  shareTotal: number;
  shares: StatementShareLine[];
  adjustTotal: number;
  adjustments: StatementAdjustLine[];
  total: number;
}
/** 可打印对账单的语言（后端 `statement.html?lang=`，ar 为右到左）。 */
export type StatementLang = "zh" | "en" | "ar";
