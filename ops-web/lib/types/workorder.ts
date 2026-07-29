// 覆盖范围：工单域（ops）——工单主体、SLA 规则、巡检计划。

export type WorkOrderType =
  | "FAULT" | "REFILL" | "INSPECT" | "INSTALL" | "REMOVE" | "COMPLAINT" | "CLEAN";
export type WorkOrderStatus =
  | "CREATED" | "DISPATCHED" | "ACCEPTED" | "PROCESSING" | "DONE" | "AUDITED" | "CLOSED";

/** 工单来源。ALERT=告警转工单，USER=投诉转工单，VENUE=场地方报障，MANUAL=运维手工开单。 */
export type WorkOrderSource = "ALERT" | "USER" | "VENUE" | "MANUAL";
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
  accept: { from: ["DISPATCHED"], to: "PROCESSING" },
  process: { from: ["PROCESSING"], to: "PROCESSING" },
  complete: { from: ["PROCESSING"], to: "DONE" },
  close: { from: ["DONE"], to: "CLOSED" },
  reject: { from: ["DISPATCHED", "PROCESSING"], to: "CREATED" },
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

/** 处理/完成的留痕入参。 */
export interface WorkOrderHandlePayload {
  handleNote: string;
  partsReplaced?: string;
  handlerName?: string;
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
export interface InspectionPlan {
  planNo: string;
  route: string;
  frequency: string;
  nextAt: string;
  assignee: string;
  active: boolean;
}
