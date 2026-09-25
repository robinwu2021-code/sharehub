import type {
  WorkOrder, WoSummary, WorkOrderDetail, WoTimelineItem, AssigneeCandidate,
  WoDeriveReq, WoTakeoverReq,
} from "../../types";
import { fail } from "../../biz-error";
import { workOrders } from "./workorder";
import { alarmRecords } from "./alarm";
import { employees } from "./org";
import { sites } from "./location";
import { getFile } from "./file";

/**
 * 工单增强的 mock：摘要 · 详情 · 派单候选人 · 派生 · 接管。
 *
 * <h3>详情里最要紧的是「关联告警」</h3>
 * 一张维修单可能同时压着好几条告警。修完不看告警就关单的话，
 * 那几条会继续躺在告警中心，而现场其实已经好了 ——
 * **告警数长期虚高，最后没人再信它**。
 *
 * <h3>照片挂在步骤上，不挂在工单上</h3>
 * 否则「这张图是修之前还是修之后拍的」永远说不清。
 */

const timelines = new Map<string, WoTimelineItem[]>();
let woSeq = 9800;

const now = () => new Date().toISOString();

function find(woNo: string): WorkOrder {
  const w = workOrders.find((x) => x.woNo === woNo);
  if (!w) fail(`工单不存在：${woNo}`, `Work order not found: ${woNo}`, `أمر العمل غير موجود: ${woNo}`);
  return w;
}

/** 追加一条时间线。**每个动作都要留痕**，否则「谁在什么时候干了什么」只能猜。 */
export function woTimelineAppend(
  woNo: string, kind: string, action: string, note?: string, fileNos: string[] = [],
): void {
  const list = timelines.get(woNo) ?? [];
  list.unshift({ kind, action, actor: "admin", note: note ?? null, faultReasonCode: null, fileNos, at: now() });
  timelines.set(woNo, list);
}

/**
 * 详情。时间线为空时**造一条建单记录**而不是给空数组 ——
 * 空时间线会让人以为「这单没人动过」，而实际上它至少被建出来过。
 */
export function getWorkOrderDetail(woNo: string): WorkOrderDetail {
  const order = find(woNo);
  const timeline = timelines.get(woNo) ?? [{
    kind: "CREATE", action: "建单", actor: order.assigneeName ?? "system",
    note: order.description ?? null, faultReasonCode: null, fileNos: [],
    at: order.createdAt ?? now(),
  }];
  const fileNos = timeline.flatMap((t) => t.fileNos);
  return {
    order,
    timeline,
    photos: fileNos.map(getFile).filter((f): f is NonNullable<typeof f> => !!f),
    // 关联告警：同一台设备上未关闭的告警都算，修完要一起看
    alarms: alarmRecords.filter((a) => a.cabinetNo === order.cabinetNo && a.status !== "CLOSED"),
  };
}

/**
 * 派单候选人。
 *
 * <p>**站点运维责任人排最前** —— 派单时第一个看到的就该是他。
 * 此前这里是写死的 STAFF 常量，运营得自己记「哪个站归谁」。
 */
export function assigneeCandidates(siteNo?: string): AssigneeCandidate[] {
  const ownerNo = siteNo ? sites.find((s) => s.siteNo === siteNo)?.opsEmployeeNo ?? null : null;
  const list: AssigneeCandidate[] = employees
    .filter((e) => e.status === "ACTIVE")
    .map((e) => ({ type: "EMPLOYEE", no: e.employeeNo, name: e.name, siteOwner: e.employeeNo === ownerNo }));
  // 责任人置顶；其余按姓名稳定排序（顺序不稳的话，同一个人每次出现在不同位置，很容易点错）
  return list.sort((a, b) => Number(b.siteOwner) - Number(a.siteOwner) || a.name.localeCompare(b.name));
}

/**
 * 摘要条。四个都是要人动手的事，按紧急度排。
 *
 * <p>`overdue` 依赖 SLA 超时扫描的结果（`wo-sla-breach-scan`）——
 * 事件驱动那半只在接单/关单时判，**没人管的单永远不会被标超时**，
 * 而那恰恰是最该出现在这一格里的。mock 这里按 `slaDueAt` 直接算，
 * 效果等价，但真实环境靠那个定时任务。
 */
export function woSummary(): WoSummary {
  const open = workOrders.filter((w) => !["CLOSED", "AUDITED"].includes(w.status));
  const t = Date.now();
  const soon = t + 4 * 3600 * 1000;
  const dueAt = (w: WorkOrder) => (w.slaDueAt ? Date.parse(w.slaDueAt.replace(" ", "T")) : NaN);
  return {
    toDispatch: open.filter((w) => w.status === "CREATED").length,
    dueSoon: open.filter((w) => { const d = dueAt(w); return !Number.isNaN(d) && d > t && d <= soon; }).length,
    overdue: open.filter((w) => { const d = dueAt(w); return !Number.isNaN(d) && d <= t; }).length,
    reviewFailed: workOrders.filter((w) => w.auditResult === "FAIL").length,
  };
}

/**
 * 派生子单：现场发现的新问题另开一张。
 *
 * <p>塞进当前单的备注里的话，那个问题**不会进任何人的待办**，
 * 也不会被 SLA 计时 —— 等于说了等于没说。
 */
export function deriveWorkOrder(woNo: string, req: WoDeriveReq): WorkOrder {
  const parent = find(woNo);
  if (!req?.type) fail("子单类型必填", "Type required", "النوع مطلوب");
  if (!req.description?.trim()) {
    fail("子单要写清发现了什么——没有描述的派生单，接手的人不知道要修什么",
      "Description required", "الوصف مطلوب");
  }
  const child: WorkOrder = {
    ...parent,
    woNo: `WO${woSeq++}`,
    type: req.type,
    source: "INSPECTION",
    sourceNo: parent.woNo,
    priority: req.priority ?? parent.priority,
    cabinetNo: req.cabinetNo ?? parent.cabinetNo,
    status: "CREATED",
    description: req.description,
    assigneeName: null,
    createdAt: now(),
  } as WorkOrder;
  workOrders.unshift(child);
  woTimelineAppend(parent.woNo, "DERIVE", "派生子单", `→ ${child.woNo}：${req.description}`);
  woTimelineAppend(child.woNo, "CREATE", "建单", `派生自 ${parent.woNo}`);
  return child;
}

/** 接管：转给另一个人。原因必填 —— 被接管的人要看得到为什么。 */
export function takeoverWorkOrder(woNo: string, req: WoTakeoverReq): WorkOrder {
  const w = find(woNo);
  if (["CLOSED", "AUDITED"].includes(w.status)) {
    fail(`「${w.status}」的工单不能接管`, `Cannot take over a ${w.status} order`, `لا يمكن الاستلام`);
  }
  if (!req?.employeeNo) fail("请选择接管人", "Assignee required", "المستلم مطلوب");
  if (!req.reason?.trim()) {
    fail("接管原因必填——被接管的人要看得到为什么换人",
      "Takeover reason required", "سبب الاستلام مطلوب");
  }
  const emp = employees.find((e) => e.employeeNo === req.employeeNo);
  if (!emp) fail(`员工不存在：${req.employeeNo}`, `Employee not found`, `الموظف غير موجود`);
  const from = w.assigneeName;
  w.assigneeName = emp.name;
  woTimelineAppend(woNo, "TAKEOVER", "接管", `${from ?? "未派单"} → ${emp.name}：${req.reason}`);
  return w;
}

/** 仅供测试重置。 */
export function __resetTimelines(): void {
  timelines.clear();
}
