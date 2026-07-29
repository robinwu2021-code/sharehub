// 覆盖范围：工单列表与派单、SLA 规则、巡检计划。
import type { PageQ, WoQ } from "../query";
import type { PageResult, WorkOrder, SlaRule, InspectionPlan } from "../../types";

export interface WorkOrderApi {
  listWorkOrders(q?: WoQ): Promise<PageResult<WorkOrder>>;
  dispatchWorkOrder(woNo: string, assignee: string): Promise<{ ok: true }>;

  // === 工单扩展 tab ===
  listSlaRules(q?: PageQ): Promise<PageResult<SlaRule>>;
  listInspectionPlans(q?: PageQ): Promise<PageResult<InspectionPlan>>;
  saveSlaRule(x: Partial<SlaRule> & { slaNo?: string }): Promise<SlaRule>;
  saveInspectionPlan(x: Partial<InspectionPlan> & { planNo?: string }): Promise<InspectionPlan>;
}
