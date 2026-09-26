import type { AuditTrail } from "./common";
// 覆盖范围：订单域（trade）——租借订单、订单异常、投诉、退款、押金与欠费、
// 预约订单、免费订单及其页头统计。
// 注：免费来源枚举 WhitelistReason 定义在 ./user（白名单归用户域），此处引用。

import type { WhitelistReason } from "./user";

export type OrderStatus =
  | "CREATED" | "DISPENSING" | "IN_USE" | "RETURNED" | "SETTLED" | "CLOSED" | "EXCEPTION";
export interface RentOrder {
  orderNo: string;
  cUserNo: string;
  cabinetNo: string;
  returnCabinetNo: string | null;
  powerbankNo: string | null;
  locationName?: string | null;
  status: OrderStatus;
  rentStartAt: string | null;
  rentEndAt: string | null;
  durationMin: number | null;
  feeAmount: number;
  depositAmount: number;
  currency: string;

  // —— 客服干预留痕（S1）：干预不能只写日志，订单本身也要看得见结果 ——
  /** 远程弹出次数（eject 干预累加）。 */
  ejectCount?: number;
  /** 最近一次远程弹出时间。 */
  lastEjectAt?: string | null;
  /** 免单减免金额（waive 干预写入；同时把 feeAmount 置 0）。 */
  waivedAmount?: number;
  /** 累计补偿金额（compensate 干预写入；mock 口径为「补至用户余额」）。 */
  compensateAmount?: number;
  /**
   * 券抵扣金额（结算时落列）。
   *
   * 与 `feeAmount` 分开：后者是**折后**应付，只有它答不出「券抵了多少」，
   * 而这个数事后推不回来（模板面额会改、折扣额还取决于当时应收）。
   */
  couponAmount?: number;
}

// —— 订单人工干预（S1：修 F0 伪实现）——
/**
 * 干预动作。前四个是客服现场处置，`refund_apply` 只提交退款申请（真正出款走
 * /orders?tab=refunds 的审批队列，不在订单页直接扣账）。
 */
export type OrderInterventionAction = "eject" | "force_return" | "waive" | "compensate" | "refund_apply";

/**
 * 干预状态机 —— **全站唯一定义**（页面按钮、mock 校验、将来后端校验共用一份，
 * 避免「按钮按一套规则渲染、服务端按另一套校验」）。`to: null` = 只留痕不改状态。
 *
 *   eject         CREATED/DISPENSING/EXCEPTION            → DISPENSING（补弹一次）
 *   force_return  DISPENSING/IN_USE/EXCEPTION             → SETTLED（写结束时间与费用）
 *   waive         IN_USE/RETURNED/SETTLED/EXCEPTION       → 状态不变（应收置 0）
 *   compensate    除 CREATED/DISPENSING 外                → 状态不变（补至余额）
 *   refund_apply  RETURNED/SETTLED/CLOSED/EXCEPTION       → 状态不变（落退款申请）
 *
 * 口径说明：已弹出的宝不能再「远程弹出」，故 eject 不含 IN_USE 及之后的状态；
 * 已关闭订单不再动账（waive 不含 CLOSED），但事后补偿仍允许（compensate 含 CLOSED）。
 */
export const ORDER_INTERVENTIONS: Record<OrderInterventionAction, { from: readonly OrderStatus[]; to: OrderStatus | null }> = {
  eject: { from: ["CREATED", "DISPENSING", "EXCEPTION"], to: "DISPENSING" },
  force_return: { from: ["DISPENSING", "IN_USE", "EXCEPTION"], to: "SETTLED" },
  waive: { from: ["IN_USE", "RETURNED", "SETTLED", "EXCEPTION"], to: null },
  compensate: { from: ["IN_USE", "RETURNED", "SETTLED", "CLOSED", "EXCEPTION"], to: null },
  refund_apply: { from: ["RETURNED", "SETTLED", "CLOSED", "EXCEPTION"], to: null },
};

/**
 * 订单状态流转留痕一条（`ord_event_log`，append 表）。
 *
 * **与干预历史（`OrderIntervention`）是两件事**：这里是「状态怎么走的」，
 * 那里是「人做了什么」。不合并，因为干预里有 `to: null` 的只留痕动作
 * （免单/补偿/退款申请改的是钱不是状态），它根本不产生一条状态流转。
 *
 * 后端四个 service（退款/押金/异常/投诉）一直在往这张表写，而在 2026-09-25
 * 补上读端点之前**写进去的东西谁也读不到** —— 排障时「这单怎么走到 EXCEPTION 的」
 * 只能翻日志。
 */
export interface OrderEvent {
  orderNo: string;
  /** 迁移前状态；建单那条为 null。 */
  fromStatus: string | null;
  toStatus: string;
  /** 事件名（动词，如 RETURN / SETTLE）——与状态是两套词汇，别混用。 */
  event: string;
  operator: string | null;
  createdAt: string;
}

/** 该干预在当前订单状态下是否合法（页面按钮据此渲染，与 mock/后端校验同一份定义）。 */
export const canIntervene = (status: OrderStatus, action: OrderInterventionAction) =>
  ORDER_INTERVENTIONS[action].from.includes(status);

/** 当前状态下可执行的干预动作（详情抽屉按钮据此生成）。 */
export const interveneActions = (status: OrderStatus): OrderInterventionAction[] =>
  (Object.keys(ORDER_INTERVENTIONS) as OrderInterventionAction[]).filter((a) => canIntervene(status, a));

/**
 * 订单干预记录（审计刚需）：谁、什么时候、对哪一单做了什么、为什么、状态怎么变的。
 * 干预原因必填 —— 沿用退款审批口径（无原因的资金/状态干预无从追责）。
 */
export interface OrderIntervention {
  interventionNo: string;
  orderNo: string;
  action: OrderInterventionAction;
  operatorName: string;
  reason: string;
  /** 涉及金额：免单减免额 / 补偿额 / 强制归还结算额 / 退款申请额；eject 无金额故可空。 */
  amount: number | null;
  /** 金额的币种（money() 需要；规格未列，与订单一致取 AED）。 */
  currency: string;
  beforeStatus: OrderStatus;
  afterStatus: OrderStatus;
  createdAt: string;
}

/** 干预入参：原因必填；compensate 必带 amount。 */
export interface OrderIntervenePayload {
  reason: string;
  /** 补偿金额（compensate 必填）；refund_apply 由退款申请回填，其余动作忽略。 */
  amount?: number;
  operatorName?: string;
}

/** 干预返回：落库后的订单 + 刚写入的干预记录（抽屉据此同步状态与时间线）。 */
export interface OrderInterveneResult {
  order: RentOrder;
  intervention: OrderIntervention;
}

// —— 订单 · 待建功能补全（trade 域）——
/**
 * 异常单状态（S2）。原来只有 `OPEN`/`HANDLED` 两态，把「已转工单/已发起退款、正等下游闭环」
 * 和「什么都没做」混为一谈，运营看不出哪些单其实已经在处理了，故拆出中间态 HANDLING。
 *
 *   PENDING  待处置
 *   HANDLING 处置中 —— 已转工单或已发起退款，结论未定，仍可继续处置/关闭
 *   HANDLED  已处置（**终态**，不再出处置按钮）
 */
export type OrderExceptionStatus = "PENDING" | "HANDLING" | "HANDLED";

export interface OrderException {
  /**
   * 异常单自己的编号（后端主键）。
   *
   * **一个订单可以有多条异常**（先「未弹出」、后「重复扣费」），所以行键必须用它 ——
   * 用 orderNo 做行键在 mock 下看不出问题（每单只造一条），真后端返回两条时
   * React 行键重复，表现是选中/更新错行，而不会报错。
   */
  exceptionNo: string;
  orderNo: string;
  type: "NOT_EJECTED" | "NOT_RETURNED" | "OVERTIME_BUYOUT" | "DOUBLE_CHARGE";
  cabinetNo: string;
  userNo: string;
  amount: number;
  currency: string;
  status: OrderExceptionStatus;
  createdAt: string;

  // —— 处置留痕（S2）：谁、什么时候、用哪种方式、结论是什么 ——
  /** 最近一次处置方式。 */
  handleAction?: ExceptionHandleAction | null;
  /** 处置结论/原因（必填，随记录永久留痕）。 */
  handleResult?: string | null;
  handledBy?: string | null;
  handledAt?: string | null;
  /** 转工单后回填的工单号（引用 workOrders.woNo 真实工单）。 */
  workOrderNo?: string | null;
  /** 发起退款后回填的退款申请号（进 /orders?tab=refunds 审批队列）。 */
  refundNo?: string | null;
}

/**
 * 异常单处置方式（三选一）。
 *
 *   work_order 转工单   —— 落一条真实工单（设备类异常交运维），异常单转「处置中」
 *   refund     发起退款 —— 落一条 PENDING 退款申请（走既有审批队列），异常单转「处置中」
 *   close      直接关闭 —— 无需下游动作，异常单转「已处置」（终态）
 */
export type ExceptionHandleAction = "work_order" | "refund" | "close";

/**
 * 异常单处置状态机 —— **全站唯一定义**（页面按钮、mock 校验、将来后端校验共用一份）。
 * HANDLED 是终态：已处置的单不再出任何处置按钮，mock 层也会拒绝。
 * 转工单/发起退款可在 HANDLING 上继续做另一种（如先转工单、后又要退款），但**同一种不可重复**
 * （已有 workOrderNo/refundNo 时再点会被 mock 层拒绝，避免重复开单、重复退款）。
 */
export const EXCEPTION_HANDLINGS: Record<ExceptionHandleAction, { from: readonly OrderExceptionStatus[]; to: OrderExceptionStatus }> = {
  work_order: { from: ["PENDING", "HANDLING"], to: "HANDLING" },
  refund: { from: ["PENDING", "HANDLING"], to: "HANDLING" },
  close: { from: ["PENDING", "HANDLING"], to: "HANDLED" },
};

/** 该处置在当前异常单状态下是否合法（列表按钮据此渲染，与 mock/后端同一份定义）。 */
export const canHandleException = (status: OrderExceptionStatus, action: ExceptionHandleAction) =>
  EXCEPTION_HANDLINGS[action].from.includes(status);

/** 当前状态下可执行的处置动作（终态返回空数组 → 列表不出按钮）。 */
export const exceptionHandleActions = (status: OrderExceptionStatus): ExceptionHandleAction[] =>
  (Object.keys(EXCEPTION_HANDLINGS) as ExceptionHandleAction[]).filter((a) => canHandleException(status, a));

/** 处置入参：结论/原因**必填**（无结论的处置事后无从追责，沿用干预/退款审批口径）。 */
export interface OrderExceptionHandlePayload {
  result: string;
  operatorName?: string;
  /**
   * 退款申请号。退款记录住在 cs.ts 而 cs.ts 反向依赖订单域，db 层不能反向 import，
   * 故由 API mock 层先调 `applyRefund` 再把号带进来（与 refund_apply 干预同一套做法）。
   */
  refundNo?: string;
}

// 押金与欠费管理（订单域 · P2）
export type DepositStatus = "HELD" | "RELEASED" | "BOUGHT_OUT" | "ARREARS"; // 冻结/已解冻/买断/欠费
export interface DepositRecord {
  depositNo: string;
  orderNo: string;
  userNo: string;
  amount: number;
  currency: string;
  status: DepositStatus;
  arrearsAmount: number; // 欠费金额（0 表示无欠费）
  createdAt: string;

  // —— 处置留痕（S1）：解冻 / 买断 / 催缴 ——
  releasedAt?: string | null;
  /** 买断金额（买断时写入，不得超过押金额）。 */
  buyoutAmount?: number | null;
  buyoutAt?: string | null;
  /** 催缴次数与最近一次催缴（欠费单只留痕，不改状态）。 */
  dunCount?: number;
  lastDunAt?: string | null;
  lastDunChannel?: DunChannel | null;
  /** 最近一次处置的操作人与说明。 */
  operatorName?: string | null;
  note?: string | null;
}

/** 催缴渠道（欠费催缴必选，决定走哪条触达通道）。 */
export type DunChannel = "SMS" | "PUSH" | "PHONE";

/** 押金处置动作。`dun` 只留痕不改状态（沿用工单 process 的口径）。 */
export type DepositAction = "release" | "buyout" | "dun";

/**
 * 押金状态机 —— 与订单干预同构，**mock 层强制执行**（非法迁移抛错）。
 *
 *   HELD    --release--> RELEASED   （订单结清，解冻押金）
 *   HELD    --buyout-->  BOUGHT_OUT （超时未还，押金转买断）
 *   ARREARS --dun-->     ARREARS    （催缴一次：次数 +1、记最后催缴时间）
 *
 * 已解冻 / 已买断是终态：不允许再解冻或再买断（重复解冻＝重复出款）。
 */
export const DEPOSIT_TRANSITIONS: Record<DepositAction, { from: readonly DepositStatus[]; to: DepositStatus }> = {
  release: { from: ["HELD"], to: "RELEASED" },
  buyout: { from: ["HELD"], to: "BOUGHT_OUT" },
  dun: { from: ["ARREARS"], to: "ARREARS" },
};

/** 该处置在当前押金状态下是否合法（列表按钮据此渲染）。 */
export const canDepositAction = (status: DepositStatus, action: DepositAction) =>
  DEPOSIT_TRANSITIONS[action].from.includes(status);

/** 当前状态下可执行的押金处置。 */
export const depositActions = (status: DepositStatus): DepositAction[] =>
  (Object.keys(DEPOSIT_TRANSITIONS) as DepositAction[]).filter((a) => canDepositAction(status, a));

/** 买断入参：金额与原因都必填（金额类操作，二次确认另在页面侧）。 */
export interface DepositBuyoutPayload {
  /**
   * 买断原因（必填）。
   *
   * **没有 amount**：买断金额由服务端定为押金全额
   * （`DepositServiceImpl`：「不得超过押金额 —— 押金抵购机款，抵不了更多」）。
   * 此前这里有个 amount，抽屉里也有输入框，而后端读的是 `note` 且根本不看金额 ——
   * 金额与原因**两个都静默丢弃**，而买断是没收用户押金。
   */
  reason: string;
}
/** 催缴入参：渠道必选，备注可空。 */
export interface ArrearsDunPayload {
  channel: DunChannel;
  note?: string;
}

// —— 售后处置（订单域 · P1，对标简电云 B1/B2）——
// 投诉问题类型：计费争议 / 未弹出 / 未归还 / 设备故障 / 其他
export type ComplaintIssueType = "BILLING_DISPUTE" | "NOT_EJECTED" | "NOT_RETURNED" | "DEVICE_FAULT" | "OTHER";
// 处理结果：退款 / 补偿 / 驳回 / 已解释
export type ComplaintResolution = "REFUND" | "COMPENSATE" | "REJECT" | "EXPLAINED";

// 投诉订单：比竞品多 workOrderNo —— 投诉可直接转工单，投诉/订单/工单三者串通（对方投诉与工单不通）。
export interface OrderComplaint {
  complaintNo: string;
  orderNo: string; // 关联租借订单（引用 orders mock 真实单号）
  userNo: string;
  issueType: ComplaintIssueType;
  description: string; // 用户描述
  screenshotUrl: string | null; // 投诉截图（列表渲染为「查看」链接）
  submittedAt: string;
  status: "PENDING" | "PROCESSING" | "RESOLVED" | "REJECTED";
  handlerName: string | null; // 处理人
  handledAt: string | null;
  resolution: ComplaintResolution | null; // 处理结果
  resolutionNote: string; // 处理说明
  workOrderNo: string | null; // 转工单后回填
}

/**
 * 客服代客登记投诉入参（电话 / 线下渠道）。C 端用户自助提交走 C 端接口自动派生，
 * 不走这里。
 *
 * 刻意**不含** complaintNo / submittedAt / status / handlerName / resolution ——
 * 新登记一律由服务端落 PENDING 并打提交时间，前端说自己是什么状态不作数
 *（同退款审批「审批人由服务端回填」的口径）。
 */
export interface ComplaintCreatePayload {
  orderNo: string;
  userNo: string;
  issueType: ComplaintIssueType;
  description: string;
  /** 投诉截图。电话投诉常常没有截图，故可空。 */
  screenshotUrl?: string;
}

/**
 * 退款申请入参（客服代客发起，落 PENDING 进审批队列，审批通过才真正出款）。
 *
 * `idempotencyKey` **必填**：双击提交 / 网络重试如果各落一笔，就是真的退两次钱。
 * 键在表单打开时生成一次并全程沿用，服务端同键直接返回已有单（同推送发送口径）。
 * 退款单号 / 申请人 / 申请时间 / 状态 / PSP 流水号一律服务端决定，故不在入参里。
 */
export interface RefundApplyPayload {
  orderNo: string;
  userNo: string;
  amount: number;
  /** 缺省跟随订单币种（服务端兜底 AED），故表单不出这一项。 */
  currency?: string;
  reason: string;
  idempotencyKey: string;
}

// 退款记录：独立审批队列（申请→审批→执行），比竞品多一条审批链。
// idempotencyKey / pspTxnNo 是资金操作可追溯的底线：前者防重复退款，后者对得上 PSP 流水。
export interface RefundRecord extends AuditTrail {
  refundNo: string;
  orderNo: string;
  userNo: string;
  amount: number;
  currency: string;
  reason: string;
  applicantName: string; // 申请人（客服/用户/系统）
  appliedAt: string;
  status: "PENDING" | "APPROVED" | "REJECTED" | "EXECUTED" | "FAILED";
  idempotencyKey: string; // 幂等键（同一键只退一次）
  pspTxnNo: string | null; // PSP 支付流水号（未执行时为空）
}

// —— 预约订单（阶段 2）——
// 竞品是电车预约充电桩；充电宝映射为「预约取宝 / 预约还位」（热门点位高峰占位）。
export interface Reservation {
  reservationNo: string;
  userNo: string;
  type: "BORROW" | "RETURN"; // 预约取宝 / 预约还位
  siteNo: string;
  siteName: string;
  cabinetNo: string | null; // 指定机柜（空 = 站点级预约）
  reservedFrom: string;
  reservedTo: string;
  holdFee: number; // 占位费（超时未取产生；规则在业务规则页配置）
  currency: string; // 规格未列，金额展示统一需要币种（AED）
  status: "PENDING" | "FULFILLED" | "EXPIRED" | "CANCELLED";
  orderNo: string | null; // 履约后关联的租借订单
}

// —— 免费订单（阶段 2）——：与免费用户白名单联动，页头做成本管控统计
export interface FreeOrder {
  orderNo: string; // 复用租借订单号
  userNo: string;
  nickname: string;
  whitelistReason: WhitelistReason; // 免费来源
  waivedAmount: number; // 减免金额
  currency: string; // 规格未列，money() 需要（AED）
  siteName: string;
  cabinetNo: string;
  startedAt: string;
  endedAt: string;
  duration: number; // 时长（分）
}

/** 免费订单页头统计（本月免费单数 / 累计减免金额），成本管控口径。 */
export interface FreeOrderStats {
  monthCount: number;
  waivedTotal: number;
  currency: string;
}
