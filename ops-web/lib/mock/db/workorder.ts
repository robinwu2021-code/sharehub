// 工单域：工单列表 / 状态机（G6 闭环）/ SLA 规则 / 巡检计划。
// 工单挂载的机柜号取自 device.ts 的 cabinets（保证与设备列表可互相搜到同一台）。
import type {
  WorkOrder, WorkOrderType, WorkOrderStatus, WorkOrderAction, WorkOrderDraft,
  WorkOrderHandlePayload, WorkOrderClosePayload, SlaRule, InspectionPlan, PageQuery,
} from "../../types";
import { WO_TRANSITIONS, canTransition } from "../../types";
import { LOCS, p, iso } from "./internal";
import { paginate, kwHit, upsert, nextNo } from "./helpers";
import { cabinets } from "./device";

const WTYPE: WorkOrderType[] = ["FAULT", "REFILL", "INSPECT", "COMPLAINT", "CLEAN"];
const WSTATUS: WorkOrderStatus[] = ["CREATED", "DISPATCHED", "PROCESSING", "DONE", "CLOSED"];
export const workOrders: WorkOrder[] = Array.from({ length: 64 }, (_, i) => ({
  woNo: `WO${70000 + i}`, type: p(WTYPE, i), source: p(["ALERT", "USER", "VENUE", "MANUAL"] as const, i),
  // 来源单号：ALERT 挂告警号、USER 挂投诉号（编号规则与 alarm.ts / cs.ts 一致，避免跨文件循环依赖）。
  sourceNo: srcNo(p(["ALERT", "USER", "VENUE", "MANUAL"] as const, i), i),
  priority: p(["LOW", "MEDIUM", "HIGH"] as const, i), cabinetNo: p(cabinets, i).cabinetNo,
  // 点位名取所在机柜的 locationName，而非另取一次 LOCS —— 工单数 64 > 机柜数 48 时两者会错位
  locationName: p(cabinets, i).locationName, status: p(WSTATUS, i), assigneeName: i % 3 === 0 ? null : p(["Ali", "Omar", "Sara", "Wang"], i),
  slaDueAt: iso(-(i % 5) * 3600_000), description: p(["柜机离线", "缺货补货", "定期巡检", "用户投诉未弹出", "清洁维护"], i),
  createdAt: iso(i * 5400_000),
  expectedAt: iso(-(i % 4 + 1) * 86400_000),
  rejectCount: 0,
}));

/** 种子数据的来源单号：与 alarmRecords（ALM40000..40013）/ orderComplaints（CPL60000..60011）对齐。 */
function srcNo(source: WorkOrder["source"], i: number): string | null {
  if (source === "ALERT") return `ALM${40000 + (i % 14)}`;
  if (source === "USER") return `CPL${60000 + (i % 12)}`;
  return null;
}

// —— 联动验证用的定点数据（G6 §5）——
// 告警转来的、投诉转来的各留一条**停在 CREATED**的工单，保证从 /alarms、/orders?tab=complaints
// 点「转工单」跳过来的链路能一路 派单→接单→处理→完成→关单 走到 CLOSED。
workOrders.unshift(
  {
    woNo: "WO70200", type: "FAULT", source: "ALERT", sourceNo: "ALM40001",
    priority: "URGENT", cabinetNo: cabinets[3].cabinetNo, locationName: cabinets[3].locationName,
    status: "CREATED", assigneeName: null, slaDueAt: iso(-2 * 3600_000),
    description: "【告警转工单】卡槽卡宝，远程弹仓两次失败", createdAt: iso(3600_000),
    expectedAt: iso(-86400_000), rejectCount: 0,
  },
  {
    woNo: "WO70300", type: "COMPLAINT", source: "USER", sourceNo: "CPL60003",
    priority: "HIGH", cabinetNo: cabinets[5].cabinetNo, locationName: cabinets[5].locationName,
    status: "CREATED", assigneeName: null, slaDueAt: iso(-4 * 3600_000),
    description: "【投诉转工单】用户反馈扫码后充电宝未弹出", createdAt: iso(7200_000),
    expectedAt: iso(-2 * 86400_000), rejectCount: 0,
  },
);

// ————————————————————————————————————————————————————————————————
// 状态机（G6）：定义在 lib/types/workorder.ts（页面按钮与本层校验共用同一份），
// 本层负责**强制执行**——非法迁移抛错，绝不默默通过。
// ————————————————————————————————————————————————————————————————
export { WO_TRANSITIONS, canTransition, nextActions } from "../../types";

const ACTION_LABEL: Record<WorkOrderAction, string> = {
  dispatch: "派单", accept: "接单", process: "提交处理结果", complete: "完成", close: "验收关单", reject: "驳回退回", rework: "验收不合格退回返工",
};

/** 状态机违规 / 必填缺失。页面侧由全局 MutationCache.onError 统一弹 notify.error。 */
export class WorkOrderTransitionError extends Error {
  constructor(readonly woNo: string, readonly action: WorkOrderAction, readonly from: WorkOrderStatus | null, msg?: string) {
    super(msg ?? `工单 ${woNo} 当前状态「${from}」不允许执行「${ACTION_LABEL[action]}」`);
    this.name = "WorkOrderTransitionError";
  }
}

const find = (woNo: string) => workOrders.find((x) => x.woNo === woNo);
const now = () => iso(0);

/**
 * 统一迁移入口：查单 → 校验合法性 → 打补丁 → 落状态。
 * 所有对外动作（dispatch/accept/process/complete/close/reject）都必须走这里，
 * 不允许任何地方直接写 `w.status = ...`，否则状态机就形同虚设。
 */
export function transitionWorkOrder(woNo: string, action: WorkOrderAction, patch: Partial<WorkOrder> = {}): WorkOrder {
  const w = find(woNo);
  if (!w) throw new WorkOrderTransitionError(woNo, action, null, `工单 ${woNo} 不存在`);
  if (!canTransition(w.status, action)) throw new WorkOrderTransitionError(woNo, action, w.status);
  Object.assign(w, patch, { status: WO_TRANSITIONS[action].to });
  return w;
}

export function createWorkOrder(x: WorkOrderDraft): WorkOrder {
  if (!x.type) throw new Error("工单类型必填");
  if (!x.cabinetNo?.trim()) throw new Error("机柜号必填");
  if (!x.description?.trim()) throw new Error("问题描述必填");
  const cab = cabinets.find((c) => c.cabinetNo === x.cabinetNo);
  const created: WorkOrder = {
    // 手工开单从 WO70400 起，避开告警转工单(70200+)/投诉转工单(70300+)两段号段
    woNo: nextNo("WO", workOrders, 70400, "woNo"),
    type: x.type,
    source: x.source ?? "MANUAL",
    sourceNo: x.sourceNo ?? null,
    priority: x.priority ?? "MEDIUM",
    cabinetNo: x.cabinetNo,
    locationName: x.locationName ?? cab?.locationName ?? null,
    status: "CREATED",
    assigneeName: null,
    slaDueAt: null,
    description: x.description,
    createdAt: now(),
    expectedAt: x.expectedAt || null,
    rejectCount: 0,
  };
  workOrders.unshift(created);
  return created;
}

export const dispatchWorkOrder = (woNo: string, assignee: string) => {
  if (!assignee?.trim()) throw new Error("派单必须指定处理人");
  return transitionWorkOrder(woNo, "dispatch", { assigneeName: assignee, dispatchedAt: now() });
};

export const acceptWorkOrder = (woNo: string, handler?: string) =>
  transitionWorkOrder(woNo, "accept", {
    acceptedAt: now(),
    handlerName: handler || find(woNo)?.assigneeName || null,
  });

/** 提交处理结果（不改状态，可多次追加）。处理说明必填，换件记录可选。 */
export const processWorkOrder = (woNo: string, x: WorkOrderHandlePayload) => {
  if (!x.handleNote?.trim()) throw new Error("处理说明必填");
  return transitionWorkOrder(woNo, "process", {
    handlerName: x.handlerName || find(woNo)?.handlerName || find(woNo)?.assigneeName || null,
    handledAt: now(), handleNote: x.handleNote, partsReplaced: x.partsReplaced || find(woNo)?.partsReplaced || null,
  });
};

export const completeWorkOrder = (woNo: string, x: WorkOrderHandlePayload) => {
  if (!x.handleNote?.trim()) throw new Error("处理说明必填");
  return transitionWorkOrder(woNo, "complete", {
    handlerName: x.handlerName || find(woNo)?.handlerName || find(woNo)?.assigneeName || null,
    handledAt: now(), handleNote: x.handleNote,
    partsReplaced: x.partsReplaced || find(woNo)?.partsReplaced || null,
    completedAt: now(),
  });
};

/** 验收关单：**必须有验收结论**，否则拒绝（关单是终态，无结论就无从追责）。 */
export const closeWorkOrder = (woNo: string, x: WorkOrderClosePayload) => {
  if (!x.auditResult) throw new Error("关单必须给出验收结论");
  return transitionWorkOrder(woNo, "close", {
    auditorName: x.auditorName || "admin", auditedAt: now(),
    auditResult: x.auditResult, auditNote: x.auditNote || null,
  });
};

/** 验收不合格退回返工：**原因必填**，回到 PROCESSING（处理人不变，无需重新派单）。 */
export const reworkWorkOrder = (woNo: string, reason: string) => {
  if (!reason?.trim()) throw new Error("退回返工必须填写不合格原因");
  const cur = find(woNo);
  return transitionWorkOrder(woNo, "rework", {
    auditorName: "admin", auditedAt: now(), auditResult: "FAIL", auditNote: reason,
    rejectCount: (cur?.rejectCount ?? 0) + 1, completedAt: null,
  });
};

/** 驳回退回重派：**原因必填**（沿用退款审批口径），退回 CREATED 并清空处理人。 */
export const rejectWorkOrder = (woNo: string, reason: string) => {
  if (!reason?.trim()) throw new Error("驳回必须填写原因");
  const cur = find(woNo);
  return transitionWorkOrder(woNo, "reject", {
    rejectReason: reason, rejectCount: (cur?.rejectCount ?? 0) + 1,
    assigneeName: null, handlerName: null, acceptedAt: null, dispatchedAt: null,
  });
};

export const listWorkOrders = (q: PageQuery & { status?: string; type?: string } = {}) =>
  paginate(workOrders, q.page, q.size, (w) =>
    kwHit(q.keyword, w.woNo, w.cabinetNo, w.locationName, w.sourceNo, w.assigneeName, w.description) &&
    (!q.status || w.status === q.status) && (!q.type || w.type === q.type));

export const slaRules: SlaRule[] = Array.from({ length: 12 }, (_, i) => ({
  slaNo: `SLA${100 + i}`, woType: p(["FAULT", "REFILL", "INSPECT", "COMPLAINT", "CLEAN"], i),
  responseMins: p([15, 30, 60], i), resolveMins: p([120, 240, 480], i),
  escalateTo: p(["运维主管", "区域经理", "运营总监"], i), active: i % 7 !== 0,
}));
export const inspectionPlans: InspectionPlan[] = Array.from({ length: 14 }, (_, i) => ({
  planNo: `IP${200 + i}`, route: `${p(LOCS, i)} → ${p(LOCS, i + 1)}`,
  frequency: p(["每日", "每周", "双周", "每月"], i), nextAt: iso(-(i % 7) * 86400_000),
  assignee: p(["Ali Hassan", "Omar Khan", "Sara Ahmed", "Wang Lei"], i), active: i % 8 !== 0,
}));

export const listSlaRules = (q: PageQuery = {}) => paginate(slaRules, q.page, q.size, (x) => kwHit(q.keyword, x.slaNo, x.woType, x.escalateTo));
export const listInspectionPlans = (q: PageQuery = {}) => paginate(inspectionPlans, q.page, q.size, (x) => kwHit(q.keyword, x.planNo, x.route, x.assignee));
export const saveSlaRule = (x: Partial<SlaRule>) => upsert(slaRules, x, "slaNo", () => nextNo("SLA", slaRules));
export const saveInspectionPlan = (x: Partial<InspectionPlan>) => upsert(inspectionPlans, x, "planNo", () => nextNo("IP", inspectionPlans));
