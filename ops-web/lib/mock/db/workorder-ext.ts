import type {
  WorkOrder, WoSummary, WorkOrderDetail, AssigneeCandidate, WoDeriveReq, WoTakeoverReq,
  WorkOrderHandlePayload, WorkOrderClosePayload, WoOps, CostRow, PageResult,
  WoAssigneeType, WoReviewStatus, WoFaultReason, WoCloseReason, WoCostBearer, WorkOrderType, WorkOrderPriority, Site,
} from "../../types";
import {
  canTransition, woSlaRemain, woCompleteRules, woDeriveBlocked, WO_DERIVABLE_TYPES, WO_OPEN_FOR_SLA,
  WO_DUE_SOON_MINUTES,
} from "../../types";
import type { WoQ, WoPoolQ, WoCostQ } from "../../api/query";
import { fail } from "../../biz-error";
import {
  workOrders, woTimelineAppend, woTimelineOf, createWorkOrder, transitionWorkOrder, WorkOrderTransitionError,
  dispatchWorkOrder as woDispatch, acceptWorkOrder as woAccept, completeWorkOrder as woComplete,
  closeWorkOrder as woClose, rejectWorkOrder as woReject,
} from "./workorder";
import { alarmRecords } from "./alarm";
import { employees } from "./org";
import { sites } from "./location";
import { agents } from "./agent";
import { cabinets } from "./device";
import { getFile, bindFile } from "./file";
import { iso } from "./internal";
import { paginate, kwHit } from "./helpers";

export { woTimelineAppend, __resetTimelines } from "./workorder";

/**
 * 工单增强的 mock：摘要 · 列表运营维度 · 详情 · 派单候选人 · 完工收紧 · 复核 · 抢单池 · 派生 · 接管 · 成本。
 *
 * **每条校验都照后端 `WoOpsServiceImpl` 抄**（mock 放行而后端拒绝，离线调一路顺、切后端当场 400）。
 *
 * <h3>详情里最要紧的是「关联告警」</h3>
 * 一张维修单可能同时压着好几条告警。修完不看告警就关单的话，
 * 那几条会继续躺在告警中心，而现场其实已经好了 —— **告警数长期虚高，最后没人再信它**。
 * 关联口径与后端 `alarms.byWorkOrder(woNo)` 一致：告警上回填了这张工单号的才算。
 *
 * <h3>时钟</h3>
 * mock 的「现在」是 `iso(0)`（种子数据的锚点），不是 `Date.now()` ——
 * 种子的 SLA 时限都相对这个锚点造，拿真实时间去比会让所有种子单一律超时两个月。
 */

/** mock 的「现在」。 */
const MOCK_NOW = () => Date.parse(iso(0));
/** mock 里的当前登录人（抢单 = 派给自己）。 */
export const MOCK_ME = "admin";

/** wo_order 上 WorkOrder 行之外的列（后端按列名读写的那批）。 */
interface WoExtra {
  assigneeType?: WoAssigneeType | null;
  reviewStatus?: WoReviewStatus | null;
  faultReasonCode?: WoFaultReason | null;
  closeReason?: WoCloseReason | null;
  costTotal?: number | null;
  costBearerType?: WoCostBearer | null;
  costBearerNo?: string | null;
}
const extras = new Map<string, WoExtra>();
const ext = (woNo: string): WoExtra => {
  let x = extras.get(woNo);
  if (!x) { x = {}; extras.set(woNo, x); }
  return x;
};

const FAULT_REASONS: readonly WoFaultReason[] = ["NETWORK", "POWER", "SLOT_MECH", "LOCK", "BATTERY", "SCREEN", "DAMAGE", "OTHER"];

function find(woNo: string): WorkOrder {
  const w = workOrders.find((x) => x.woNo === woNo);
  if (!w) fail(`工单不存在：${woNo}`, `Work order not found: ${woNo}`, `أمر العمل غير موجود: ${woNo}`);
  return w;
}

const siteOf = (w: WorkOrder) => (w.cabinetNo ? cabinets.find((c) => c.cabinetNo === w.cabinetNo)?.siteNo ?? null : null);
/** 关联告警：告警上回填了本工单号的（后端 byWorkOrder）。 */
const linkedAlarms = (woNo: string) => alarmRecords.filter((a) => a.workOrderNo === woNo);
const remainOf = (w: WorkOrder) => woSlaRemain({ ...w, ops: null }, MOCK_NOW());

/** 行 + 运营维度（后端 enrich + portal 层填 alarmCount）。 */
export function withOps(w: WorkOrder): WorkOrder {
  const x = extras.get(w.woNo) ?? {};
  const ops: WoOps = {
    siteNo: siteOf(w),
    assigneeType: x.assigneeType ?? null,
    slaRemainMinutes: remainOf(w),
    reviewStatus: x.reviewStatus ?? null,
    faultReasonCode: x.faultReasonCode ?? null,
    closeReason: x.closeReason ?? null,
    mergedIntoWoNo: null,
    alarmRecoveredAt: null,
    alarmCount: linkedAlarms(w.woNo).length,
  };
  return { ...w, ops };
}

// —— 种子：让「代理的单超时可接管」「复核未通过」「成本」三块离线也有东西可看 ——
// 只动 ext 的列与少量 SLA 时限，不改状态（状态分布是别的报表的种子）。
(function seedExtras() {
  const enabled = agents.filter((a) => a.status === "ENABLED");
  workOrders.forEach((w, k) => {
    const open = WO_OPEN_FOR_SLA.includes(w.status);
    // 每 4 张未完工的单里有 1 张已超时（1~3 小时），否则「已超时」格离线永远是 0
    if (open && k % 4 === 0) w.slaDueAt = iso(((k % 3) + 1) * 3600_000);
    if (w.assigneeName && ["DISPATCHED", "PROCESSING"].includes(w.status) && k % 5 === 2 && enabled.length) {
      w.assigneeName = enabled[k % enabled.length].agentNo;
      ext(w.woNo).assigneeType = "AGENT";
      w.slaDueAt = iso(((k % 2) + 2) * 3600_000);   // 代理的单超时 2~3 小时 → 可被平台接管
    } else if (w.assigneeName) {
      ext(w.woNo).assigneeType = "EMPLOYEE";
    }
    if (w.status === "DONE" && w.source === "ALERT") ext(w.woNo).reviewStatus = "FAILED";
    if (w.status === "CLOSED") {
      const x = ext(w.woNo);
      x.closeReason = "RESOLVED";
      if (w.type === "FAULT") x.faultReasonCode = FAULT_REASONS[k % FAULT_REASONS.length];
      if (k % 2 === 0) {
        x.costTotal = 40 + ((k * 37) % 260);
        const site = siteOf(w);
        x.costBearerType = k % 6 === 0 && enabled.length ? "AGENT" : "SITE";
        x.costBearerNo = x.costBearerType === "AGENT" ? enabled[k % enabled.length].agentNo : site;
      }
    }
  });
})();

// ——————————————————————— 列表 / 摘要 ———————————————————————

const slaHit = (w: WorkOrder, state?: string) => {
  if (!state) return true;
  if (!WO_OPEN_FOR_SLA.includes(w.status)) return false;
  const r = remainOf(w);
  if (r == null) return false;
  if (state === "OVERDUE") return r < 0;
  if (state === "DUE_SOON") return r >= 0 && r < WO_DUE_SOON_MINUTES;
  fail(`slaState 非法：${state}`, `Invalid slaState: ${state}`, `slaState غير صالح`);
};

/** 工单列表（后端 WoQuery 的全部筛选；`status` 可逗号多值）。 */
export function listWorkOrdersRich(q: WoQ = {}): PageResult<WorkOrder> {
  const statuses = q.status ? String(q.status).split(",") : null;
  const r = paginate(workOrders, q.page, q.size, (w) =>
    kwHit(q.keyword, w.woNo, w.cabinetNo, w.locationName, w.sourceNo, w.assigneeName, w.description)
    && (!statuses || statuses.includes(w.status))
    && (!q.type || w.type === q.type)
    && (!q.priority || w.priority === q.priority)
    && (!q.source || w.source === q.source)
    && (!q.siteNo || siteOf(w) === q.siteNo)
    && (!q.assigneeNo || w.assigneeName === q.assigneeNo)
    && (!q.reviewStatus || (extras.get(w.woNo)?.reviewStatus ?? null) === q.reviewStatus)
    && slaHit(w, q.slaState));
  return { ...r, list: r.list.map(withOps) };
}

/**
 * 摘要条。四个都是要人动手的事，按紧急度排；口径与后端 summary() 同：
 * 即将超时 = 两小时内到期（{@link WO_DUE_SOON_MINUTES}），复核未通过 = DONE 且 reviewStatus=FAILED。
 *
 * <p>`overdue` 在真实环境依赖 SLA 超时扫描（`wo-sla-breach-scan`）——
 * 事件驱动那半只在接单/关单时判，**没人管的单永远不会被标超时**。mock 按时限直接算，效果等价。
 */
export function woSummary(): WoSummary {
  const open = workOrders.filter((w) => WO_OPEN_FOR_SLA.includes(w.status));
  const remains = open.map(remainOf).filter((r): r is number => r != null);
  return {
    toDispatch: workOrders.filter((w) => w.status === "CREATED").length,
    dueSoon: remains.filter((r) => r >= 0 && r < WO_DUE_SOON_MINUTES).length,
    overdue: remains.filter((r) => r < 0).length,
    reviewFailed: workOrders.filter((w) => w.status === "DONE" && extras.get(w.woNo)?.reviewStatus === "FAILED").length,
  };
}

// ——————————————————————— 详情 ———————————————————————

/**
 * 详情。时间线为空时**造一条建单记录**而不是给空数组 ——
 * 空时间线会让人以为「这单没人动过」，而实际上它至少被建出来过。
 */
export function getWorkOrderDetail(woNo: string): WorkOrderDetail {
  const order = find(woNo);
  const timeline = woTimelineOf(woNo) ?? [{
    kind: "CREATE", action: "CREATE", actor: "system",
    note: order.description ?? null, faultReasonCode: null, fileNos: [],
    at: order.createdAt ?? new Date().toISOString(),
  }];
  const fileNos = [...new Set(timeline.flatMap((t) => t.fileNos))];
  return {
    order: withOps(order),
    timeline,
    photos: fileNos.map(getFile).filter((f): f is NonNullable<typeof f> => !!f),
    alarms: linkedAlarms(woNo),
  };
}

// ——————————————————————— 派单 ———————————————————————

/**
 * 站点的运营代理（`loc_site_agent.role=OPERATE`）。列表行 `Site` 不带这一列（后端只在 SiteOps 详情里给），
 * mock 的站点行上有就用、没有就当平台自营。
 */
const opAgent = (s: Site): string | null => (s as Site & { operateAgentNo?: string | null }).operateAgentNo ?? null;

/** 站点运维责任人（后端 resolveOwner）：运营代理启用中 → 代理；否则在职的员工责任人。 */
function resolveOwner(siteNo?: string | null): { type: WoAssigneeType; no: string; name: string } | null {
  const s = siteNo ? sites.find((x) => x.siteNo === siteNo) : undefined;
  if (!s) return null;
  const ag = opAgent(s) ? agents.find((a) => a.agentNo === opAgent(s) && a.status === "ENABLED") : undefined;
  if (ag) return { type: "AGENT", no: ag.agentNo, name: ag.name };
  const emp = s.opsEmployeeNo ? employees.find((e) => e.employeeNo === s.opsEmployeeNo && e.status === "ACTIVE") : undefined;
  return emp ? { type: "EMPLOYEE", no: emp.employeeNo, name: emp.name } : null;
}

/**
 * 派单候选人（后端 candidates）：**站点运维责任人置顶**，其后该站运营代理，再后 OPS 角色在职员工。
 * 此前派单抽屉是写死的 STAFF 常量，运营得自己记「哪个站归谁」。
 * 员工部分按姓名稳定排序（顺序不稳的话，同一个人每次出现在不同位置，很容易点错）。
 */
export function assigneeCandidates(siteNo?: string): AssigneeCandidate[] {
  const out: AssigneeCandidate[] = [];
  const seen = new Set<string>();
  const owner = resolveOwner(siteNo);
  if (owner) { out.push({ type: owner.type, no: owner.no, name: owner.name, siteOwner: true }); seen.add(owner.no); }
  const s = siteNo ? sites.find((x) => x.siteNo === siteNo) : undefined;
  const ag = s && opAgent(s) ? agents.find((a) => a.agentNo === opAgent(s) && a.status === "ENABLED") : undefined;
  if (ag && !seen.has(ag.agentNo)) { out.push({ type: "AGENT", no: ag.agentNo, name: ag.name, siteOwner: false }); seen.add(ag.agentNo); }
  const ops = employees
    .filter((e) => e.status === "ACTIVE" && (e.roleNos ?? [e.roleNo]).includes("OPS") && !seen.has(e.employeeNo))
    .sort((a, b) => a.name.localeCompare(b.name) || a.employeeNo.localeCompare(b.employeeNo));
  for (const e of ops) out.push({ type: "EMPLOYEE", no: e.employeeNo, name: e.name, siteOwner: false });
  return out;
}

/**
 * 派单：状态机在 workorder.ts。
 *
 * ⚠️ **照抄后端的缺口**：后端 `POST /{woNo}/dispatch` 只写 assignee_name，**不写 assignee_type**
 * （只有告警自动派单 openOrAttach、抢单、接管会写）。于是人工派给代理的单 `ops.assigneeType` 为空 ——
 * 平台接管的「只接代理的单」判不出来，成本也会记到站点而不是代理。mock 不替后端补这一列：
 * 补了的话离线一路能接管，切真后端就 409，正是「mock 放行而后端拒绝」那种坑。已报后端。
 */
export function dispatchWorkOrderExt(woNo: string, assignee: string): WorkOrder {
  return woDispatch(woNo, assignee);
}

/** 驳回退回待派（同后端：只清受理人，assignee_type 列后端也不清 —— 见上）。 */
export function rejectWorkOrderExt(woNo: string, reason: string): WorkOrder {
  return woReject(woNo, reason);
}

/** 仅供测试：模拟后端自动派单（openOrAttach 按站点责任人派）写下的受理人类型。 */
export function __setAssigneeType(woNo: string, t: WoAssigneeType | null): void {
  ext(woNo).assigneeType = t;
}

// ——————————————————————— 完工 · 复核 · 验收 ———————————————————————

/**
 * 完工（后端 complete 的收紧版）：先过状态机，再按类型查必填，再落照片 / 原因 / 成本，最后做复核。
 * 校验顺序与后端一致 —— 状态不对的单先报状态，不报「缺照片」。
 */
export function completeWorkOrderExt(woNo: string, x: WorkOrderHandlePayload): WorkOrder {
  const w = find(woNo);
  if (!canTransition(w.status, "complete")) throw new WorkOrderTransitionError(woNo, "complete", w.status);
  const rules = woCompleteRules(w.type);
  const fileNos = [...new Set((x.fileNos ?? []).filter((f) => !!f?.trim()))];
  if (rules.photos && fileNos.length === 0) {
    fail("请上传至少一张现场照片", "Upload at least one on-site photo", "يرجى رفع صورة ميدانية واحدة على الأقل");
  }
  if (x.faultReasonCode && !FAULT_REASONS.includes(x.faultReasonCode)) {
    fail(`故障原因非法：${x.faultReasonCode}`, `Invalid fault reason: ${x.faultReasonCode}`, `سبب عطل غير صالح`);
  }
  if (rules.faultReason && !x.faultReasonCode) fail("请选择故障原因", "Select a fault reason", "يرجى اختيار سبب العطل");
  if (rules.countedQty && (x.countedQty == null || x.countedQty < 0)) {
    fail("撤机单必须填写现场清点的宝数——与系统在柜数比对，不填就没有比对依据",
      "Removal orders require the counted power-bank quantity", "أوامر الإزالة تتطلب عدد البطاريات المعدودة");
  }
  if ((x.partCost ?? 0) < 0 || (x.laborCost ?? 0) < 0) fail("成本金额不能为负", "Cost cannot be negative", "لا يمكن أن تكون التكلفة سالبة");
  for (const f of fileNos) if (!getFile(f)) fail(`照片不存在：${f}`, `File not found: ${f}`, `الملف غير موجود: ${f}`);

  const done = woComplete(woNo, {
    ...x, fileNos,
    partsReplaced: x.partChanged ? "PART_CHANGED" : x.partsReplaced,
  });
  fileNos.forEach(bindFile);
  const e = ext(woNo);
  if (x.faultReasonCode) e.faultReasonCode = x.faultReasonCode;
  // 成本（G4）：代理运维的单记代理，其余记站点
  if (x.partCost != null || x.laborCost != null) {
    const add = (x.partCost ?? 0) + (x.laborCost ?? 0);
    e.costTotal = (e.costTotal ?? 0) + add;
    e.costBearerType = e.assigneeType === "AGENT" ? "AGENT" : "SITE";
    e.costBearerNo = e.costBearerType === "AGENT" ? done.assigneeName : siteOf(done);
  }
  review(done);
  return done;
}

/**
 * 完工复核（后端由告警域订阅 WorkOrderCompletedEvent 后回调 recordReview）：
 * 告警来源且有关联告警的单 —— 关联告警全部已关闭 → 自动验收关单（SYSTEM）；否则标「复核未通过」、停在 DONE。
 */
function review(w: WorkOrder) {
  if (w.source !== "ALERT") return;
  const linked = linkedAlarms(w.woNo);
  if (linked.length === 0) return;
  const still = linked.filter((a) => a.status !== "CLOSED");
  const e = ext(w.woNo);
  if (still.length === 0) {
    e.reviewStatus = "PASSED";
    woTimelineAppend(w.woNo, "DISPATCH", "REVIEW", "复核通过，自动验收", [], { actor: "SYSTEM" });
    woClose(w.woNo, { auditResult: "PASS", auditNote: "复核通过，自动验收", auditorName: "SYSTEM" });
    e.closeReason = "RESOLVED";
  } else {
    e.reviewStatus = "FAILED";
    woTimelineAppend(w.woNo, "DISPATCH", "REVIEW",
      `复核未通过：${still.map((a) => a.alarmNo).join("、")} 仍未恢复`, [], { actor: "SYSTEM" });
  }
}

/** 验收关单（后端 close）：FAIL 不许关（走返工）；复核未通过还放行，必须写明理由。 */
export function closeWorkOrderExt(woNo: string, x: WorkOrderClosePayload): WorkOrder {
  const w = find(woNo);
  if (x.auditResult === "FAIL") {
    fail("验收不合格（FAIL）不允许关单，请走「退回返工」", "A failed acceptance cannot close the order; send it back for rework", "لا يمكن الإغلاق عند الفشل");
  }
  if (extras.get(woNo)?.reviewStatus === "FAILED" && !x.auditNote?.trim()) {
    fail("复核未通过的工单人工验收时，请在验收说明里写明放行理由",
      "Explain why you accept an order whose review failed", "يرجى توضيح سبب القبول رغم فشل المراجعة");
  }
  const done = woClose(w.woNo, x);
  ext(woNo).closeReason = "RESOLVED";
  return done;
}

// ——————————————————————— 抢单池 ———————————————————————

/** 抢单池（后端 pool）：未派出的工单（CREATED），可按类型筛。 */
export function listWoPool(q: WoPoolQ = {}): PageResult<WorkOrder> {
  const r = paginate(workOrders, q.page, q.size, (w) => w.status === "CREATED" && (!q.type || w.type === q.type));
  return { ...r, list: r.list.map(withOps) };
}

/**
 * 抢单（后端 grab）：派给自己 + 接单，两条边都过状态机，落在 ACCEPTED。
 * 已被别人派走 / 抢走的单报「不在池中」—— 与后端条件更新失败同一个结果。
 */
export function grabWorkOrder(woNo: string): WorkOrder {
  const w = find(woNo);
  if (w.status !== "CREATED") {
    fail(`工单 ${woNo} 已不在抢单池（已被派出或抢走）`, `Work order ${woNo} is no longer in the pool`, `أمر العمل لم يعد متاحاً`);
  }
  woDispatch(woNo, MOCK_ME);
  const done = woAccept(woNo, MOCK_ME);
  ext(woNo).assigneeType = "EMPLOYEE";
  woTimelineAppend(woNo, "DISPATCH", "NOTE", "抢单（派给自己并接单）", [], { actor: MOCK_ME });
  return done;
}

// ——————————————————————— 派生 · 接管 ———————————————————————

/**
 * 巡检派生（后端 derive，D4）：巡检现场发现问题直接开维修 / 补宝 / 清洁单，来源挂巡检单号。
 *
 * <p>塞进当前单的备注里的话，那个问题**不会进任何人的待办**，也不会被 SLA 计时。
 */
export function deriveWorkOrder(woNo: string, req: WoDeriveReq): WorkOrder {
  const parent = find(woNo);
  const blocked = woDeriveBlocked(parent);
  if (blocked) fail(`工单 ${woNo} 不能派生：${blocked}`, `Cannot derive from ${woNo}`, `لا يمكن الاشتقاق`);
  if (!req?.description?.trim()) {
    fail("子单要写清发现了什么——没有描述的派生单，接手的人不知道要修什么", "Description required", "الوصف مطلوب");
  }
  const type = (req.type ?? "").toUpperCase() as WorkOrderType;
  if (!WO_DERIVABLE_TYPES.includes(type)) {
    fail(`巡检只能派生维修 / 补宝 / 清洁单，不能是 ${req.type}`, `Invalid type: ${req.type}`, `نوع غير صالح`);
  }
  const cabinetNo = req.cabinetNo?.trim() || parent.cabinetNo;
  if (!cabinetNo) fail("派生单要挂到一台机柜上", "Cabinet required", "الخزانة مطلوبة");
  const child = createWorkOrder({
    type, cabinetNo, locationName: parent.locationName ?? undefined,
    priority: (req.priority as WorkOrderPriority) || "MEDIUM",
    description: `【巡检 ${woNo} 发现】${req.description.trim()}`,
    source: "INSPECTION", sourceNo: `${woNo}:${type}:${cabinetNo}`,
  });
  woTimelineAppend(woNo, "HANDLE", "NOTE", `NOTE: 派生 ${child.woNo}（${type}）`);
  return child;
}

/**
 * 平台接管（后端 takeover，F4）：代理承接、已派出、**SLA 已超时**的单改派平台员工。
 * 员工不指定 = 站点员工责任人，没有就取第一位在职 OPS。原因后端可空。
 */
export function takeoverWorkOrder(woNo: string, req: WoTakeoverReq = {}): WorkOrder {
  const w = find(woNo);
  const x = ext(woNo);
  if (x.assigneeType !== "AGENT") {
    fail(`工单 ${woNo} 不是代理承接的单，不能平台接管`, `Only agent-assigned orders can be taken over`, `فقط أوامر الوكيل`);
  }
  if (!["DISPATCHED", "ACCEPTED", "PROCESSING"].includes(w.status)) {
    fail(`「${w.status}」的工单不能接管`, `Cannot take over a ${w.status} order`, `لا يمكن الاستلام`);
  }
  const remain = remainOf(w);
  if (remain == null || remain >= 0) {
    fail(`工单 ${woNo} 的 SLA 尚未超时，不能接管`, `SLA not breached yet`, `لم يتم تجاوز اتفاقية مستوى الخدمة`);
  }
  let empNo = req.employeeNo?.trim() || null;
  if (empNo) {
    const emp = employees.find((e) => e.employeeNo === empNo);
    if (!emp || emp.status !== "ACTIVE") fail(`员工不存在或已停用：${empNo}`, `Employee not found or inactive`, `الموظف غير موجود`);
  } else {
    const owner = resolveOwner(siteOf(w));
    empNo = owner?.type === "EMPLOYEE" ? owner.no
      : employees.find((e) => e.status === "ACTIVE" && (e.roleNos ?? [e.roleNo]).includes("OPS"))?.employeeNo ?? null;
    if (!empNo) fail("找不到可接管的平台运维", "No operator available", "لا يوجد مشغل متاح");
  }
  const from = w.assigneeName;
  // 换人 = 退回待派（REJECT）再派出（DISPATCH），两条边都过状态机
  transitionWorkOrder(woNo, "reject", { assigneeName: null, handlerName: null, acceptedAt: null });
  transitionWorkOrder(woNo, "dispatch", { assigneeName: empNo, dispatchedAt: iso(0) });
  x.assigneeType = "EMPLOYEE";
  woTimelineAppend(woNo, "DISPATCH", "TAKEOVER",
    `平台接管（代理 ${from ?? "-"} 超时）→ ${empNo}${req.reason?.trim() ? `：${req.reason.trim()}` : ""}`, [], { actor: empNo });
  return w;
}

// ——————————————————————— 成本 ———————————————————————

/** 工单成本汇总（后端 costSummary）：完工单按承担方聚合，金额降序。`to` 不含当天（左闭右开）。 */
export function listWoCosts(q: WoCostQ = {}): CostRow[] {
  const from = q.from ? Date.parse(`${q.from}T00:00:00Z`) : null;
  const to = q.to ? Date.parse(`${q.to}T00:00:00Z`) : null;
  const groups = new Map<string, CostRow>();
  for (const w of workOrders) {
    const x = extras.get(w.woNo);
    if (x?.costTotal == null || !["DONE", "AUDITED", "CLOSED"].includes(w.status)) continue;
    const at = Date.parse(w.createdAt);
    if (from != null && at < from) continue;
    if (to != null && at >= to) continue;
    if (q.bearerType && x.costBearerType !== q.bearerType.toUpperCase()) continue;
    const key = `${x.costBearerType}|${x.costBearerNo}`;
    const g = groups.get(key) ?? { bearerType: x.costBearerType ?? "SITE", bearerNo: x.costBearerNo ?? null, orders: 0, total: 0, currency: "AED" };
    g.orders += 1;
    g.total = Math.round((g.total + x.costTotal) * 100) / 100;
    groups.set(key, g);
  }
  return [...groups.values()].sort((a, b) => b.total - a.total);
}
