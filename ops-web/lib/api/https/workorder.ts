// 覆盖范围：工单开单 + 全状态流转（G6 闭环）、SLA 规则、巡检计划。
// 端点前缀：/api/ops/**
// 约定：流转动作一律 POST /work-orders/{no}/{action}，服务端**必须**做同一套状态机校验，
// 非法迁移返回 4xx（前端按钮禁用只是体验，不是防线）。
import { client } from "../http-client";
import type { WorkOrderApi } from "../contracts/workorder";
import type { PageQ, WoQ } from "../query";

export const workOrderHttp: WorkOrderApi = {
  listWorkOrders: (q?: WoQ) => client.get("/api/ops/work-orders", q),
  createWorkOrder: (x) => client.post("/api/ops/work-orders", x),
  dispatchWorkOrder: (no, assignee) => client.post(`/api/ops/work-orders/${no}/dispatch`, { assignee }),
  acceptWorkOrder: (no, handler) => client.post(`/api/ops/work-orders/${no}/accept`, { handler }),
  processWorkOrder: (no, x) => client.post(`/api/ops/work-orders/${no}/process`, x),
  completeWorkOrder: (no, x) => client.post(`/api/ops/work-orders/${no}/complete`, x),
  closeWorkOrder: (no, x) => client.post(`/api/ops/work-orders/${no}/close`, x),
  rejectWorkOrder: (no, reason) => client.post(`/api/ops/work-orders/${no}/reject`, { reason }),
  reworkWorkOrder: (no, reason) => client.post(`/api/ops/work-orders/${no}/rework`, { reason }),

  // 工单扩展
  listSlaRules: (q?: PageQ) => client.get("/api/ops/sla-rules", q),
  listInspectionPlans: (q?: PageQ) => client.get("/api/ops/inspection-plans", q),
  saveSlaRule: (x) => client.post(x.slaNo ? `/api/ops/sla-rules/${x.slaNo}` : "/api/ops/sla-rules", x),
  saveInspectionPlan: (x) => client.post(x.planNo ? `/api/ops/inspection-plans/${x.planNo}` : "/api/ops/inspection-plans", x),
};
