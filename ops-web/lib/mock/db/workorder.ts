// 工单域：工单列表 / SLA 规则 / 巡检计划。
// 工单挂载的机柜号取自 device.ts 的 cabinets（保证与设备列表可互相搜到同一台）。
import type { WorkOrder, WorkOrderType, WorkOrderStatus, SlaRule, InspectionPlan, PageQuery } from "../../types";
import { LOCS, p, iso } from "./internal";
import { paginate, kwHit, upsert, nextNo } from "./helpers";
import { cabinets } from "./device";

const WTYPE: WorkOrderType[] = ["FAULT", "REFILL", "INSPECT", "COMPLAINT", "CLEAN"];
const WSTATUS: WorkOrderStatus[] = ["CREATED", "DISPATCHED", "PROCESSING", "DONE", "CLOSED"];
export const workOrders: WorkOrder[] = Array.from({ length: 64 }, (_, i) => ({
  woNo: `WO${70000 + i}`, type: p(WTYPE, i), source: p(["ALERT", "USER", "VENUE", "MANUAL"] as const, i),
  priority: p(["LOW", "MEDIUM", "HIGH"] as const, i), cabinetNo: p(cabinets, i).cabinetNo,
  locationName: p(LOCS, i), status: p(WSTATUS, i), assigneeName: i % 3 === 0 ? null : p(["Ali", "Omar", "Sara", "Wang"], i),
  slaDueAt: iso(-(i % 5) * 3600_000), description: p(["柜机离线", "缺货补货", "定期巡检", "用户投诉未弹出", "清洁维护"], i),
  createdAt: iso(i * 5400_000),
}));

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
