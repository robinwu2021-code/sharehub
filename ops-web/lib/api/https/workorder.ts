// 覆盖范围：工单列表与派单、SLA 规则、巡检计划。
// 端点前缀：/api/ops/**
import { client } from "../http-client";
import type { WorkOrderApi } from "../contracts/workorder";
import type { PageQ, WoQ } from "../query";

export const workOrderHttp: WorkOrderApi = {
  listWorkOrders: (q?: WoQ) => client.get("/api/ops/work-orders", q),
  dispatchWorkOrder: (no, assignee) => client.post(`/api/ops/work-orders/${no}/dispatch`, { assignee }),

  // 工单扩展
  listSlaRules: (q?: PageQ) => client.get("/api/ops/sla-rules", q),
  listInspectionPlans: (q?: PageQ) => client.get("/api/ops/inspection-plans", q),
  saveSlaRule: (x) => client.post(x.slaNo ? `/api/ops/sla-rules/${x.slaNo}` : "/api/ops/sla-rules", x),
  saveInspectionPlan: (x) => client.post(x.planNo ? `/api/ops/inspection-plans/${x.planNo}` : "/api/ops/inspection-plans", x),
};
