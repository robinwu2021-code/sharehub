// 覆盖范围：工单域（ops）——工单主体、SLA 规则、巡检计划。

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
export type WorkOrderSource = "ALERT" | "USER" | "VENUE" | "MANUAL" | "PLAN";
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
