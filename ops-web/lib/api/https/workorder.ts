// 覆盖范围：工单开单 + 全状态流转（G6 闭环）、SLA 规则、巡检计划。
// 端点前缀：/api/ops/**
// 约定：流转动作一律 POST /work-orders/{no}/{action}，服务端**必须**做同一套状态机校验，
// 非法迁移返回 4xx（前端按钮禁用只是体验，不是防线）。
import { client } from "../http-client";
import type { WorkOrderApi } from "../contracts/workorder";
import type { PageQ, WoQ, WoPoolQ, WoCostQ } from "../query";

export const workOrderHttp: WorkOrderApi = {
  listWorkOrders: (q?: WoQ) => client.get("/api/ops/work-orders", q),
  createWorkOrder: (x) => client.post("/api/ops/work-orders", x),
  dispatchWorkOrder: (no, assignee) => client.post(`/api/ops/work-orders/${no}/dispatch`, { assignee }),
  acceptWorkOrder: (no, handler) => client.post(`/api/ops/work-orders/${no}/accept`, { handler }),
  // T0-1：后端动作名是 /handle（WoExtController），此前前端发 /process 直接 404。
  processWorkOrder: (no, x) => client.post(`/api/ops/work-orders/${no}/handle`, x),
  closeWorkOrder: (no, x) => client.post(`/api/ops/work-orders/${no}/close`, x),
  // 完工 / 驳回 / 返工：WoExtController 已补齐（原 T1-C 缺口）。完工入参即后端 HandleReq。
  completeWorkOrder: (no, x) => client.post(`/api/ops/work-orders/${no}/complete`, x),
  rejectWorkOrder: (no, reason) => client.post(`/api/ops/work-orders/${no}/reject`, { reason }),
  reworkWorkOrder: (no, reason) => client.post(`/api/ops/work-orders/${no}/rework`, { reason }),

  // 工单扩展
  listSlaRules: (q?: PageQ) => client.get("/api/ops/sla-rules", q),
  listInspectionPlans: (q?: PageQ) => client.get("/api/ops/inspection-plans", q),
  getSlaRule: (no) => client.get(`/api/ops/sla-rules/${no}`),
  getInspectionPlan: (no) => client.get(`/api/ops/inspection-plans/${no}`),
  saveSlaRule: (x) => client.post(x.slaNo ? `/api/ops/sla-rules/${x.slaNo}` : "/api/ops/sla-rules", x),
  saveInspectionPlan: (x) => client.post(x.planNo ? `/api/ops/inspection-plans/${x.planNo}` : "/api/ops/inspection-plans", x),
  // 巡检计划「立即执行一次」：后端 InspectionPlanService.run（幂等：同计划同周期只一次，周期键服务端算）。
  runInspectionPlan: (no) => client.post(`/api/ops/inspection-plans/${no}/run`),

  // —— 工单增强 ——
  woSummary: () => client.get("/api/ops/work-orders/summary"),
  getWorkOrderDetail: (no) => client.get(`/api/ops/work-orders/${no}`),
  assigneeCandidates: (siteNo) => client.get("/api/ops/work-orders/assignee-candidates", { siteNo }),
  deriveWorkOrder: (no, req) => client.post(`/api/ops/work-orders/${no}/derive`, req),
  takeoverWorkOrder: (no, req) => client.post(`/api/ops/work-orders/${no}/takeover`, req),
  listWoPool: (q?: WoPoolQ) => client.get("/api/ops/work-orders/pool", q),
  grabWorkOrder: (no) => client.post(`/api/ops/work-orders/${no}/grab`),
  listWoCosts: (q?: WoCostQ) => client.get("/api/ops/work-orders/costs", q),
};
