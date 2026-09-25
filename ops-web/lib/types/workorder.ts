// 覆盖范围：工单域（ops）——工单主体、SLA 规则、巡检计划。
import type { FileRef } from "./file";
import type { AlarmRecord } from "./alarm";

export type WorkOrderType =
  | "FAULT" | "REFILL" | "INSPECT" | "INSTALL" | "REMOVE" | "COMPLAINT" | "CLEAN";
export type WorkOrderStatus =
  | "CREATED" | "DISPATCHED" | "ACCEPTED" | "PROCESSING" | "DONE" | "AUDITED" | "CLOSED";

/**
 * 工单来源。ALERT=告警转工单，USER=投诉转工单，VENUE=场地方报障，MANUAL=运维手工开单，
 * PLAN=巡检计划触发（S7「立即执行一次」生成，`sourceNo` 挂计划号 IP*）。
 * PLAN 单独成一档而不复用 MANUAL：计划生成的批量工单必须能被认回它的计划，
 * 否则「这一堆巡检单哪来的」在列表上说不清，也无从做重复执行的判定。
 */
// INSPECTION=巡检派生（2026-09-25 批次 D4）：巡检现场开出的维修 / 补宝 / 清洁单，sourceNo 前缀是巡检单号
export type WorkOrderSource = "ALERT" | "USER" | "VENUE" | "MANUAL" | "PLAN" | "INSPECTION";
export type WorkOrderPriority = "LOW" | "MEDIUM" | "HIGH" | "URGENT";

/**
 * 工单状态机的动作。**与状态一一对应不是一回事**：process 只留痕不改状态
 * （处理中可多次提交处理进展），reject 是退回重派。合法迁移见 WO_TRANSITIONS。
 */
export type WorkOrderAction = "dispatch" | "accept" | "process" | "complete" | "close" | "reject" | "rework";

/**
 * 验收结论：关单必须给结论。
 * - `PASS` 验收合格 → 关单
 * - `PASS_WITH_ISSUE` 有条件通过（遗留问题写进 auditNote）→ 关单
 * - `FAIL` 验收不合格 → **不关单，走 rework 退回返工**（原先只有前两种，
 *   意味着验收人一旦发现没修好也只能捏着鼻子关单，是状态机的漏洞）
 */
export type WoAuditResult = "PASS" | "PASS_WITH_ISSUE" | "FAIL";

/**
 * 工单状态机 —— **全站唯一定义**（本文件是类型层，页面与 mock/后端契约共用同一份，
 * 避免「按钮按一套规则渲染、服务端按另一套校验」的经典错位）。
 *
 *   CREATED --dispatch--> DISPATCHED --accept--> PROCESSING --complete--> DONE --close--> CLOSED
 *   DISPATCHED / PROCESSING --reject--> CREATED（退回重派）
 *   PROCESSING --process--> PROCESSING（提交处理进展，只留痕不改状态，可多次）
 *
 * 遗留状态 ACCEPTED / AUDITED 不参与本状态机（早期枚举，i18n 与 Badge 仍保留其文案）。
 */
export const WO_TRANSITIONS: Record<WorkOrderAction, { from: readonly WorkOrderStatus[]; to: WorkOrderStatus }> = {
  dispatch: { from: ["CREATED"], to: "DISPATCHED" },
  // 接单落 ACCEPTED，**不是直接跳 PROCESSING**：后端 WoStateMachine 的 ACCEPT 边就是
  // DISPATCHED→ACCEPTED，而这里此前写成 →PROCESSING。
  // 后果不是「状态名不好看」：真后端接完单工单停在 ACCEPTED，
  // 而这张表里没有任何动作的 from 含 ACCEPTED —— nextActions() 返回空数组，
  // **那张工单在界面上一个按钮都没有**，处理/完工/驳回全部消失，且不报错。
  accept: { from: ["DISPATCHED"], to: "ACCEPTED" },
  // ACCEPTED→PROCESSING 是真迁移；PROCESSING→PROCESSING 是自环（处理中可多次提交进展，
  // 只留痕不改状态）。后端 handle() 对 PROCESSING 显式跳过状态机，就是为了这个自环。
  process: { from: ["ACCEPTED", "PROCESSING"], to: "PROCESSING" },
  complete: { from: ["PROCESSING"], to: "DONE" },
  // 后端 /close 一次走完 AUDIT→CLOSE（DONE→AUDITED→CLOSED）：AUDITED 是过程态，
  // 不单独出按钮，所以这里直接 DONE→CLOSED。
  close: { from: ["DONE"], to: "CLOSED" },
  reject: { from: ["DISPATCHED", "ACCEPTED", "PROCESSING"], to: "CREATED" },
  // 验收不合格退回返工：回到 PROCESSING 而非 CREATED —— 处理人不变，不用重新派单
  rework: { from: ["DONE"], to: "PROCESSING" },
};

/** 该动作在当前状态下是否合法（页面按钮据此渲染，与 mock/后端校验同一份定义）。 */
export const canTransition = (status: WorkOrderStatus, action: WorkOrderAction) =>
  WO_TRANSITIONS[action].from.includes(status);

/** 当前状态下的下一步可选动作（列表操作列 / 看板卡片按钮据此生成）。 */
export const nextActions = (status: WorkOrderStatus): WorkOrderAction[] =>
  (Object.keys(WO_TRANSITIONS) as WorkOrderAction[]).filter((a) => canTransition(status, a));

export interface WorkOrder {
  woNo: string;
  type: WorkOrderType;
  source: WorkOrderSource;
  /** 来源单号：ALERT→告警号 ALM*，USER→投诉号 CPL*；手工开单为 null。 */
  sourceNo?: string | null;
  priority: WorkOrderPriority;
  cabinetNo: string | null;
  locationName?: string | null;
  status: WorkOrderStatus;
  assigneeName: string | null;
  slaDueAt: string | null;
  description: string;
  createdAt: string;

  // —— 期望完成时间（开单时填，用于超期提示）——
  expectedAt?: string | null;

  // —— 流转留痕（G6 闭环）：每一步的人 + 时间 + 说明，关单后可完整回溯 ——
  dispatchedAt?: string | null;
  acceptedAt?: string | null;
  /** 处理人（接单人）。派单对象是 assigneeName，接单后落为处理人。 */
  handlerName?: string | null;
  handledAt?: string | null;
  handleNote?: string | null;
  /** 换件记录（可选），如「更换锁扣模块 ×1」。 */
  partsReplaced?: string | null;
  completedAt?: string | null;
  auditorName?: string | null;
  auditedAt?: string | null;
  auditResult?: WoAuditResult | null;
  auditNote?: string | null;
  /** 最近一次驳回原因（退回重派时必填）。 */
  rejectReason?: string | null;
  rejectCount?: number;

  /**
   * 运营维度（后端 `WoOps`，2026-09-25 承接业务告警时追加）。
   * 列表 / 详情都带；动作端点的回包可能不带（老构造点），所以可空。
   */
  ops?: WoOps | null;
}

/** 开单入参（页面「新建工单」抽屉提交的形状）。 */
export interface WorkOrderDraft {
  type: WorkOrderType;
  cabinetNo: string;
  locationName?: string;
  priority: WorkOrderPriority;
  description: string;
  expectedAt?: string;
  source?: WorkOrderSource;
  sourceNo?: string | null;
}

/**
 * 处理 / 完工入参（后端 `HandleReq`）。
 *
 * 完工按类型收紧（后端 `WoOpsServiceImpl.complete`，前端规则见 {@link woCompleteRules}）：
 * 维修 / 装机 / 撤机必须有现场照片；维修必须给故障原因；撤机必须填清点数。
 */
export interface WorkOrderHandlePayload {
  handleNote: string;
  /** 仅 mock 留痕用的自由文本；后端库里只有 `partChanged` 布尔位（出参 partsReplaced=PART_CHANGED）。 */
  partsReplaced?: string;
  handlerName?: string;
  /** 处理人编号；空 = 后端按当前受理人。 */
  assigneeNo?: string | null;
  /** 是否更换了配件（后端 `part_changed`）。 */
  partChanged?: boolean | null;
  deviceChanged?: boolean | null;
  /** 故障原因分类（字典 wo_fault_reason）。维修单完工必填。 */
  faultReasonCode?: WoFaultReason | null;
  /** 现场照片（文件服务 fileNo，用途 WO_PHOTO）。 */
  fileNos?: string[] | null;
  /** 装机现场扫码的点位号。 */
  locationNo?: string | null;
  /** 撤机现场清点的宝数。撤机单必填 —— 与系统在柜数比对，不一致挂资产差异。 */
  countedQty?: number | null;
  /** 配件金额（AED，≥0）。 */
  partCost?: number | null;
  /** 人工金额（AED，≥0）。 */
  laborCost?: number | null;
}
/** 验收关单入参。 */
export interface WorkOrderClosePayload {
  auditResult: WoAuditResult;
  auditNote?: string;
  auditorName?: string;
}

// —— 工单 · 待建功能补全（ops 域）——
export interface SlaRule {
  slaNo: string;
  woType: string;
  responseMins: number;
  resolveMins: number;
  escalateTo: string;
  active: boolean;
}
/**
 * 巡检频率。**具名而不是内联联合**：两端同名词表比对
 * （后端 StatusVocabularyAcrossEndsTest）只认具名 `export type`。
 */
export type InspectionFrequency = "DAILY" | "WEEKLY" | "BIWEEKLY" | "MONTHLY";

export interface InspectionPlan {
  planNo: string;
  route: string;
  /**
   * 巡检频率。**值是后端枚举（DAILY/WEEKLY/…），不是中文标签** ——
   * 此前这里是 string 且全链路存的是「每日」「每周」，而后端返回 DAILY/WEEKLY：
   * {@link inspectionPeriodKey} 按中文字面匹配，真后端下**一个分支都匹配不上**，
   * 全部落到兜底的「按天」——「同一计划同一周期只开一批」的幂等保护
   * 就此退化成「按天」，周计划可以每天开一次，而且不报错。
   * 中文是展示层的事（见 work-orders 页的 INSPECT_FREQ 标签表）。
   */
  frequency: InspectionFrequency;
  /**
   * 调度表达式，**执行口径以此为准**（后端 WoInspectionPlan 注释原话）。
   * {@link frequency} 是它的人读描述，两者一旦不一致，只看 frequency 的界面
   * 就在谎报这个计划什么时候真的跑。
   */
  cron: string;
  nextAt: string;
  assignee: string;
  active: boolean;

  // —— S7 手动触发留痕（mock 无定时概念，「按计划自动开工单」只能手动执行一次）——
  /** 最近一次执行时间。 */
  lastRunAt?: string | null;
  /** 最近一次执行所属周期键（幂等键，见 inspectionPeriodKey）——同周期第二次执行会被拒。 */
  lastRunPeriod?: string | null;
  /** 最近一次执行生成的工单号（详情/提示里回显，让人能点回工单列表核对）。 */
  lastRunWoNos?: string[];
}

/** 「立即执行一次」的返回：本次落了哪几张工单，属于哪个周期。 */
export interface InspectionRunResult {
  planNo: string;
  period: string;
  woNos: string[];
}

/**
 * 巡检计划的**周期键** = 幂等键。频率决定粒度：每日按天、每周/双周按自然周序、每月按月。
 * 同一计划在同一周期内只允许执行一次——否则连点两下就会给同一条路线开出两批重复巡检单，
 * 接后端后更是重复派工。周次用「年内自然周序」而非 ISO 周（跨年归属规则复杂且此处不需要），
 * 只要求同一年内单调、跨周必变。
 */
export const inspectionPeriodKey = (frequency: string, at: Date = new Date()): string => {
  const y = at.getUTCFullYear();
  const day = `${y}-${String(at.getUTCMonth() + 1).padStart(2, "0")}-${String(at.getUTCDate()).padStart(2, "0")}`;
  const week = Math.floor((Date.UTC(y, at.getUTCMonth(), at.getUTCDate()) - Date.UTC(y, 0, 1)) / 604800_000) + 1;
  if (frequency === "DAILY") return day;
  if (frequency === "WEEKLY") return `${y}-W${String(week).padStart(2, "0")}`;
  if (frequency === "BIWEEKLY") return `${y}-B${String(Math.ceil(week / 2)).padStart(2, "0")}`;
  if (frequency === "MONTHLY") return day.slice(0, 7);
  // 未知频率（导入或后端给了枚举外的值）：退化到按天。
  // **退化意味着幂等保护变弱**（周计划会变成每天可开一次），所以这里不是
  // 「安全默认」而是「最后兜底」—— 真出现了应该去查为什么会有枚举外的值。
  return day;
};

/**
 * 能否「立即执行一次」：不能则返回原因，能则返回 null。
 * **页面按钮与 mock 校验共用这一份**（同 couponIssuable 的做法），杜绝「按钮亮着点了报错」。
 */
export const inspectionRunnable = (plan: InspectionPlan, at: Date = new Date()): string | null => {
  if (!plan.active) return "计划已停用，启用后才能执行";
  const period = inspectionPeriodKey(plan.frequency, at);
  if (plan.lastRunPeriod === period) {
    return `本周期（${period}）已执行过，生成了 ${plan.lastRunWoNos?.length ?? 0} 张巡检工单；下个周期才能再执行`;
  }
  return null;
};

// ─────────────────────────────────────────────────────────────
// 工单增强：摘要条 · 详情（时间线 + 照片 + 关联告警）· 派单候选人 · 派生与接管
// ─────────────────────────────────────────────────────────────

/**
 * 工单摘要条（后端 `WoSummary`）。
 *
 * <p>四个数**都是要人动手的事**，而且按紧急度排：
 * 待派单 → 即将超时 → 已超时 → 验收不通过。
 * 不放「工单总数」——它不会让任何人去做任何事。
 *
 * <p>`overdue` 能有数，靠的是 `wo-sla-breach-scan` 定时扫
 * （事件驱动那半只在接单/关单时判，**没人管的单永远不会被标超时**，
 * 而那恰恰是最该出现在这一格里的）。
 */
export interface WoSummary {
  toDispatch: number;
  dueSoon: number;
  overdue: number;
  reviewFailed: number;
}

/**
 * 派单候选人（后端 `AssigneeCandidate`）。
 *
 * <p>`siteOwner` 标出**这个站点的运维责任人** —— 派单时把他排在最前面。
 * 此前派单抽屉的候选人是写死的 `STAFF` 常量，
 * 于是运营得自己记住「哪个站归谁」，记错了单子就派给了另一个城市的人。
 */
export interface AssigneeCandidate {
  /** `EMPLOYEE` / `AGENT`。 */
  type: string;
  no: string;
  name: string;
  siteOwner: boolean;
}

/**
 * 工单时间线的一条（后端 `TimelineItem`）。
 *
 * <p>`kind` 区分这条来自哪一步（派单 / 接单 / 处理 / 完工 / 验收…），
 * `fileNos` 是该步骤留下的照片 —— 照片挂在**步骤**上而不是工单上，
 * 否则「这张图是修之前还是修之后拍的」就永远说不清。
 */
export interface WoTimelineItem {
  kind: string;
  action: string | null;
  actor: string | null;
  note: string | null;
  /** 故障原因码。完工时必填，是统计「这类故障占比」的唯一依据。 */
  faultReasonCode: string | null;
  fileNos: string[];
  at: string;
}

/**
 * 工单详情（后端 `WorkOrderDetailView`）。
 *
 * <p>**关联告警是这里最要紧的一块**：一张维修单可能同时压着好几条告警，
 * 修完不看告警就关单的话，那几条告警会继续躺在告警中心，
 * 而现场其实已经好了 —— 告警数长期虚高，最后没人再信它。
 */
export interface WorkOrderDetail {
  order: WorkOrder;
  timeline: WoTimelineItem[];
  photos: FileRef[];
  alarms: AlarmRecord[];
}

/** 派生子单（现场发现的新问题另开一张，而不是塞进当前单的备注里）。 */
export interface WoDeriveReq {
  type: string;
  priority?: string | null;
  cabinetNo?: string | null;
  description: string;
}

/**
 * 平台接管（后端 `TakeoverReq`，F4）：代理承接的单 SLA 超时后改派平台员工。
 * `employeeNo` 空 = 后端按站点员工责任人 / 区域负载自动选；`reason` 后端可空，
 * 界面要求必填（写进时间线，被接管的代理看得到为什么）。
 */
export interface WoTakeoverReq {
  employeeNo?: string | null;
  reason?: string | null;
}

// —— 运营维度词表（具名：两端同名的由后端 StatusVocabularyAcrossEndsTest 自动比对）——

/** 完工复核结果（后端 `WoReviewStatus`，仅告警来源的工单）。PASSED 自动验收 / FAILED 待人工返工或放行。 */
export type WoReviewStatus = "PASSED" | "FAILED";
/** 故障原因分类（后端 `WoFaultReason`，字典 wo_fault_reason）。维修单完工必填。 */
export type WoFaultReason = "NETWORK" | "POWER" | "SLOT_MECH" | "LOCK" | "BATTERY" | "SCREEN" | "DAMAGE" | "OTHER";
/** 受理人类型：平台员工 / 代理商。只有代理承接的单能被平台接管。 */
export type WoAssigneeType = "EMPLOYEE" | "AGENT";
/** 关单原因（`wo_order.close_reason`）。 */
export type WoCloseReason = "RESOLVED" | "INVALID" | "DUPLICATE" | "WITHDRAWN";
/** 工单成本承担方（`wo_order.cost_bearer_type`）：代理运维的单记代理，其余记站点效益。 */
export type WoCostBearer = "SITE" | "AGENT";
/** 列表的 SLA 筛选（后端 `WoQuery.slaState`）：两小时内到期 / 已超时（只看未完工的）。 */
export type WoSlaState = "DUE_SOON" | "OVERDUE";

/** 工单的运营维度（后端 `WoOps`）。 */
export interface WoOps {
  siteNo: string | null;
  assigneeType: WoAssigneeType | null;
  /** 距解决时限的分钟数；**已超时为负**；已完工 / 无 SLA 为 null。 */
  slaRemainMinutes: number | null;
  reviewStatus: WoReviewStatus | null;
  faultReasonCode: WoFaultReason | null;
  closeReason: WoCloseReason | null;
  mergedIntoWoNo: string | null;
  alarmRecoveredAt: string | null;
  /** 关联的业务告警数。 */
  alarmCount: number;
}

/** 工单成本汇总一行（后端 `CostRow`，G4）：按承担方聚合完工工单的配件 + 人工金额。 */
export interface CostRow {
  bearerType: WoCostBearer;
  /** 站点号（SITE）或代理号（AGENT）。 */
  bearerNo: string | null;
  orders: number;
  total: number;
  currency: string | null;
}

/** 未完工（SLA 仍在计时）的状态 —— 与后端 `OPEN_FOR_SLA` 同一份。 */
export const WO_OPEN_FOR_SLA: readonly WorkOrderStatus[] = ["CREATED", "DISPATCHED", "ACCEPTED", "PROCESSING"];
/**
 * 「即将超时」的窗口（分钟）。**与后端摘要条 / `slaState=DUE_SOON` 同口径：两小时。**
 * 方案 §8.4 写的是 30 分钟，后端落的是 2 小时 —— 列表的黄标用同一个数，
 * 否则摘要条说「即将超时 18」，点进去列表里却只有 3 行是黄的。
 */
export const WO_DUE_SOON_MINUTES = 120;

/**
 * SLA 剩余分钟数（负 = 已超时；null = 已完工或没有 SLA）。
 * 优先用后端算好的 `ops.slaRemainMinutes`（服务端时钟），拿不到才按 `slaDueAt` 自己算。
 * `slaDueAt` 后端给的是空格分隔的本地时间串（"2026-09-23 11:00:00"），解析前补 T。
 */
export function woSlaRemain(w: WorkOrder, now: number = Date.now()): number | null {
  if (w.ops && w.ops.slaRemainMinutes !== undefined) return w.ops.slaRemainMinutes;
  if (!WO_OPEN_FOR_SLA.includes(w.status) || !w.slaDueAt) return null;
  const due = Date.parse(w.slaDueAt.includes("T") ? w.slaDueAt : w.slaDueAt.replace(" ", "T"));
  return Number.isNaN(due) ? null : Math.floor((due - now) / 60_000);
}

/**
 * 完工的类型化必填项（与后端 `WoOpsServiceImpl.complete` 同一套，mock 与完工抽屉共用）。
 * - 维修 / 装机 / 撤机：现场照片 ≥ 1 张
 * - 维修：故障原因分类
 * - 撤机：清点宝数（与系统在柜数比对，不填就没有比对依据）
 * - 装机：现场扫码点位（后端可空，界面给出但不强制）
 */
export function woCompleteRules(type: WorkOrderType) {
  return {
    photos: type === "FAULT" || type === "INSTALL" || type === "REMOVE",
    faultReason: type === "FAULT",
    countedQty: type === "REMOVE",
    locationNo: type === "INSTALL",
  };
}

/** 巡检能派生的工单类型（后端 `DERIVABLE`）：现场能判断、且需要另派人 / 另排时间处理的。 */
export const WO_DERIVABLE_TYPES: readonly WorkOrderType[] = ["FAULT", "REFILL", "CLEAN"];

/**
 * 能否从这张单派生子单：不能返回原因，能返回 null（按钮禁用提示与 mock 校验共用）。
 * 后端：只有巡检单、且巡检员已到场（ACCEPTED / PROCESSING / DONE）。
 */
export function woDeriveBlocked(w: WorkOrder): string | null {
  if (w.type !== "INSPECT") return "只有巡检单能派生：巡检现场发现的问题另开维修 / 补宝 / 清洁单";
  if (!["ACCEPTED", "PROCESSING", "DONE"].includes(w.status)) return "巡检员接单到场后（已接单 / 处理中 / 已完成）才能派生";
  return null;
}

/**
 * 能否平台接管：不能返回原因，能返回 null（后端 `takeover` 的三道校验，界面据此禁用并说明）。
 * 超时判定后端看 wo_sla 的响应 / 解决两个超时标记；界面只拿得到解决时限的剩余分钟，
 * 所以「响应超时但解决未超时」的单这里会误判为不可接管 —— 宁可少给一个按钮，不给一个必然 409 的按钮。
 */
export function woTakeoverBlocked(w: WorkOrder, now: number = Date.now()): string | null {
  if (w.ops?.assigneeType !== "AGENT") return "只有代理承接的工单可以平台接管（平台员工的单直接驳回重派）";
  if (!["DISPATCHED", "ACCEPTED", "PROCESSING"].includes(w.status)) return "只有已派单 / 已接单 / 处理中的工单可以接管";
  const remain = woSlaRemain(w, now);
  if (remain == null || remain >= 0) {
    return remain == null ? "该单没有 SLA 时限，无从判定超时" : `SLA 未超时（剩 ${fmtMinutes(remain)}），超时后才能接管`;
  }
  return null;
}

/** 分钟数 → 「1 天 3 小时」「2 小时 5 分」「40 分钟」（取绝对值；正负由调用方表达）。 */
export function fmtMinutes(min: number): string {
  const m = Math.abs(Math.round(min));
  if (m >= 1440) { const d = Math.floor(m / 1440), h = Math.floor((m % 1440) / 60); return h ? `${d} 天 ${h} 小时` : `${d} 天`; }
  if (m >= 60) { const h = Math.floor(m / 60), r = m % 60; return r ? `${h} 小时 ${r} 分` : `${h} 小时`; }
  return `${m} 分钟`;
}
