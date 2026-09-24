// 工单域：工单列表 / 状态机（G6 闭环）/ SLA 规则 / 巡检计划。
// 工单挂载的机柜号取自 device.ts 的 cabinets（保证与设备列表可互相搜到同一台）。
import type {
  WorkOrder, WorkOrderType, WorkOrderStatus, WorkOrderAction, WorkOrderDraft,
  WorkOrderHandlePayload, WorkOrderClosePayload, SlaRule, InspectionPlan, InspectionRunResult, PageQuery,
} from "../../types";
import { WO_TRANSITIONS, canTransition, inspectionPeriodKey, inspectionRunnable } from "../../types";
import { fail } from "@/lib/biz-error";
import { LOCS, p, iso } from "./internal";
import { paginate, kwHit, upsert, nextNo } from "./helpers";
import { cabinets } from "./device";

const WTYPE: WorkOrderType[] = ["FAULT", "REFILL", "INSPECT", "COMPLAINT", "CLEAN"];
const WSTATUS: WorkOrderStatus[] = ["CREATED", "DISPATCHED", "PROCESSING", "DONE", "CLOSED"];
/** 派单/处理人取自员工表的同一批姓名（org.ts employees 用的也是这 5 个）。 */
const WO_STAFF = ["Ali Hassan", "Omar Khan", "Sara Ahmed", "Wang Lei", "Fatima N."];

export const workOrders: WorkOrder[] = Array.from({ length: 64 }, (_, i) => ({
  woNo: `WO${70000 + i}`, type: p(WTYPE, i), source: p(["ALERT", "USER", "VENUE", "MANUAL"] as const, i),
  // 来源单号：ALERT 挂告警号、USER 挂投诉号（编号规则与 alarm.ts / cs.ts 一致，避免跨文件循环依赖）。
  sourceNo: srcNo(p(["ALERT", "USER", "VENUE", "MANUAL"] as const, i), i),
  priority: p(["LOW", "MEDIUM", "HIGH"] as const, i), cabinetNo: p(cabinets, i).cabinetNo,
  // 点位名取所在机柜的 locationName，而非另取一次 LOCS —— 工单数 64 > 机柜数 48 时两者会错位
  locationName: p(cabinets, i).locationName, status: p(WSTATUS, i),
  // 派单对象用**员工真名**（与 org.ts 的 employees 同一批），不再用 "Ali" 这类短名。
  // 短名与 employees[].name（"Ali Hassan"）对不上，导致「绩效报表」永远算不出是谁处理的 ——
  // 一张只能按名字关联的表，名字对不上就等于没有关联。
  assigneeName: i % 3 === 0 ? null : p(WO_STAFF, i),
  slaDueAt: iso(-(i % 5) * 3600_000), description: p(["柜机离线", "缺货补货", "定期巡检", "用户投诉未弹出", "清洁维护"], i),
  createdAt: iso(i * 5400_000),
  expectedAt: iso(-(i % 4 + 1) * 86400_000),
  rejectCount: 0,
  // —— 流转留痕：按状态回填，只填「已经走到的那几步」——
  // 此前这几列全空，于是「平均解决时长」无源可算、绩效页只能靠编。
  ...flowTrace(p(WSTATUS, i), i),
}));


/**
 * 按状态回填流转留痕。**只填走到过的步骤**：CREATED 什么都没有，DISPATCHED 只有派单时间，
 * 以此类推 —— 给一张待派单的工单填上完工时间，比不填更误导。
 *
 * 时长刻意做出差异（`45 + (i*17)%180` 分钟）而非常数：绩效报表要按人算「平均解决时长」，
 * 常数会让所有人分数一样，那个页面就永远看不出差别。
 */
function flowTrace(status: WorkOrderStatus, i: number) {
  const base = i * 5400_000;                     // 与 createdAt 同基准
  const dispatchMs = 10 * 60_000;                // 建单 10 分钟后派单
  const acceptMs = dispatchMs + 15 * 60_000;     // 派单 15 分钟后接单
  const resolveMin = 45 + (i * 17) % 180;        // 接单 → 完工：45~224 分钟
  const handler = p(WO_STAFF, i);
  const reached = (s: WorkOrderStatus) => WSTATUS.indexOf(status) >= WSTATUS.indexOf(s);
  return {
    dispatchedAt: reached("DISPATCHED") ? iso(base - dispatchMs) : null,
    acceptedAt: reached("PROCESSING") ? iso(base - acceptMs) : null,
    handlerName: reached("PROCESSING") ? handler : null,
    handledAt: reached("PROCESSING") ? iso(base - acceptMs) : null,
    completedAt: reached("DONE") ? iso(base - acceptMs - resolveMin * 60_000) : null,
    auditorName: reached("CLOSED") ? "admin" : null,
    auditedAt: reached("CLOSED") ? iso(base - acceptMs - resolveMin * 60_000 - 600_000) : null,
  };
}

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
  if (!x.type) throw fail("工单类型必填", "Work order type is required", "نوع أمر العمل مطلوب");
  if (!x.cabinetNo?.trim()) throw fail("机柜号必填", "Cabinet number is required", "رقم الخزانة مطلوب");
  if (!x.description?.trim()) throw fail("问题描述必填", "A problem description is required", "وصف المشكلة مطلوب");
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
  if (!assignee?.trim()) throw fail("派单必须指定处理人", "Dispatching requires an assignee", "الإسناد يتطلب تحديد منفّذ");
  return transitionWorkOrder(woNo, "dispatch", { assigneeName: assignee, dispatchedAt: now() });
};

export const acceptWorkOrder = (woNo: string, handler?: string) =>
  transitionWorkOrder(woNo, "accept", {
    acceptedAt: now(),
    handlerName: handler || find(woNo)?.assigneeName || null,
  });

/** 提交处理结果（不改状态，可多次追加）。处理说明必填，换件记录可选。 */
export const processWorkOrder = (woNo: string, x: WorkOrderHandlePayload) => {
  if (!x.handleNote?.trim()) throw fail("处理说明必填", "Handling notes are required", "ملاحظات المعالجة مطلوبة");
  return transitionWorkOrder(woNo, "process", {
    handlerName: x.handlerName || find(woNo)?.handlerName || find(woNo)?.assigneeName || null,
    handledAt: now(), handleNote: x.handleNote, partsReplaced: x.partsReplaced || find(woNo)?.partsReplaced || null,
  });
};

export const completeWorkOrder = (woNo: string, x: WorkOrderHandlePayload) => {
  if (!x.handleNote?.trim()) throw fail("处理说明必填", "Handling notes are required", "ملاحظات المعالجة مطلوبة");
  return transitionWorkOrder(woNo, "complete", {
    handlerName: x.handlerName || find(woNo)?.handlerName || find(woNo)?.assigneeName || null,
    handledAt: now(), handleNote: x.handleNote,
    partsReplaced: x.partsReplaced || find(woNo)?.partsReplaced || null,
    completedAt: now(),
  });
};

/** 验收关单：**必须有验收结论**，否则拒绝（关单是终态，无结论就无从追责）。 */
export const closeWorkOrder = (woNo: string, x: WorkOrderClosePayload) => {
  if (!x.auditResult) throw fail("关单必须给出验收结论", "Closing requires an acceptance result", "الإغلاق يتطلب نتيجة قبول");
  return transitionWorkOrder(woNo, "close", {
    auditorName: x.auditorName || "admin", auditedAt: now(),
    auditResult: x.auditResult, auditNote: x.auditNote || null,
  });
};

/** 验收不合格退回返工：**原因必填**，回到 PROCESSING（处理人不变，无需重新派单）。 */
export const reworkWorkOrder = (woNo: string, reason: string) => {
  if (!reason?.trim()) throw fail("退回返工必须填写不合格原因", "Sending back for rework requires the reason it failed acceptance", "الإعادة للتصحيح تتطلب ذكر سبب عدم القبول");
  const cur = find(woNo);
  return transitionWorkOrder(woNo, "rework", {
    auditorName: "admin", auditedAt: now(), auditResult: "FAIL", auditNote: reason,
    rejectCount: (cur?.rejectCount ?? 0) + 1, completedAt: null,
  });
};

/** 驳回退回重派：**原因必填**（沿用退款审批口径），退回 CREATED 并清空处理人。 */
export const rejectWorkOrder = (woNo: string, reason: string) => {
  if (!reason?.trim()) throw fail("驳回必须填写原因", "Rejecting requires a reason", "الرفض يتطلب ذكر السبب");
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
  // 值用后端枚举，不是中文标签 —— 中文是展示层的事（见 work-orders 页 INSPECT_FREQ）。
  // 此前存中文，而 inspectionPeriodKey 按中文匹配，切真后端后幂等周期会退化成按天。
  frequency: p(["DAILY", "WEEKLY", "BIWEEKLY", "MONTHLY"] as const, i),
  // 执行口径以 cron 为准；与 frequency 对应，别让两者说两套话
  cron: p(["0 8 * * *", "0 8 * * 1", "0 8 * * 1/2", "0 8 1 * *"], i),
  nextAt: iso(-(i % 7) * 86400_000),
  assignee: p(["Ali Hassan", "Omar Khan", "Sara Ahmed", "Wang Lei"], i), active: i % 8 !== 0,
  // 种子一律「本周期未执行过」：否则页面一进来一半计划的按钮就是灰的，看不出功能在哪
  lastRunAt: null, lastRunPeriod: null, lastRunWoNos: [],
}));

export const listSlaRules = (q: PageQuery = {}) => paginate(slaRules, q.page, q.size, (x) => kwHit(q.keyword, x.slaNo, x.woType, x.escalateTo));
export const listInspectionPlans = (q: PageQuery = {}) => paginate(inspectionPlans, q.page, q.size, (x) => kwHit(q.keyword, x.planNo, x.route, x.assignee));
export const saveSlaRule = (x: Partial<SlaRule>) => upsert(slaRules, x, "slaNo", () => nextNo("SLA", slaRules));

/**
 * 增改巡检计划。**执行留痕字段一律剥离**（同 savePushMessage 的加固）：
 * 否则表单里塞一个 `lastRunPeriod` 就能伪造「本周期已执行」，或者反过来把它抹掉重跑一遍。
 * 这些字段只有 runInspectionPlanNow 能写。
 */
export const saveInspectionPlan = (x: Partial<InspectionPlan>) => {
  const { lastRunAt: _a, lastRunPeriod: _p, lastRunWoNos: _w, ...clean } = x;
  const row = upsert(inspectionPlans, clean, "planNo", () => nextNo("IP", inspectionPlans));
  // 新建的计划要有留痕字段的初值，否则「上次执行」列拿到 undefined
  row.lastRunAt ??= null; row.lastRunPeriod ??= null; row.lastRunWoNos ??= [];
  return row;
};

/** 「立即执行一次」被拒（停用 / 本周期已执行 / 路线上找不到机柜）。页面走全局 onError 弹错。 */
export class InspectionRunError extends Error {
  constructor(msg: string) { super(msg); this.name = "InspectionRunError"; }
}

/**
 * 巡检计划「立即执行一次」：手动补上 mock 里不存在的定时器，**真的生成工单**
 * （落 workOrders，工单列表/看板当场可见），来源 `PLAN` + `sourceNo=planNo` 挂回计划。
 *
 * 幂等：同一计划在同一周期（见 inspectionPeriodKey）只能执行一次，第二次整批拒绝——
 * 连点两下不会开出两批重复巡检单。判定用的是与页面按钮同一个 `inspectionRunnable`。
 *
 * 一站一张工单：巡检对象是站点，机柜号取该站在册的第一台（工单必须挂真实机柜，
 * 逐柜检查结果写在处理说明里），路线上任一站在台账里找不到机柜则整批拒绝、不做半成功。
 * `nextAt` **不推进**：手动补跑不代表计划周期到了，改它会让排期看起来已经走过。
 */
export function runInspectionPlanNow(planNo: string): InspectionRunResult {
  const plan = inspectionPlans.find((x) => x.planNo === planNo);
  if (!plan) throw new InspectionRunError(`巡检计划 ${planNo} 不存在`);
  const reason = inspectionRunnable(plan);
  if (reason) throw new InspectionRunError(`巡检计划 ${planNo} 无法执行：${reason}`);

  // 路线形如「A → B」：拆成站点，逐站找一台在册机柜
  const stops = plan.route.split("→").map((s) => s.trim()).filter(Boolean);
  if (stops.length === 0) throw new InspectionRunError(`巡检计划 ${planNo} 的路线为空，无法生成工单`);
  const picked = stops.map((stop) => ({
    stop,
    cab: cabinets.find((c) => c.locationName === stop && !c.archivedAt && c.status !== "RETIRED") ?? null,
  }));
  const missing = picked.filter((x) => !x.cab).map((x) => x.stop);
  if (missing.length) {
    throw new InspectionRunError(
      `巡检计划 ${planNo} 的站点「${missing.join("、")}」在设备台账里没有在册机柜，本次不生成任何工单`,
    );
  }

  const period = inspectionPeriodKey(plan.frequency);
  const woNos = picked.map(({ stop, cab }) => {
    const w = createWorkOrder({
      type: "INSPECT", cabinetNo: cab!.cabinetNo, locationName: stop, priority: "LOW",
      description: `【巡检计划 ${planNo}】${plan.route} · ${stop} 例行巡检（${plan.frequency}）`,
      source: "PLAN", sourceNo: planNo, expectedAt: plan.nextAt,
    });
    // 计划自带负责人，生成即派给他——但走状态机的 dispatch，不直接写 assigneeName
    dispatchWorkOrder(w.woNo, plan.assignee);
    return w.woNo;
  });

  plan.lastRunAt = now();
  plan.lastRunPeriod = period;
  plan.lastRunWoNos = woNos;
  return { planNo, period, woNos };
}
