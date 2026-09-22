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
  // T0-1：后端动作名是 /handle（WoExtController），此前前端发 /process 直接 404。
  processWorkOrder: (no, x) => client.post(`/api/ops/work-orders/${no}/handle`, x),
  closeWorkOrder: (no, x) => client.post(`/api/ops/work-orders/${no}/close`, x),
  // ⚠️ T1-C 后端缺口：下列三个动作端点后端尚未实现（WoExtController 只有
  // dispatch/accept/handle/close）。USE_MOCK=0 时必然 404 —— 页面按钮需同步禁用或后端补齐。
  completeWorkOrder: (no, x) => client.post(`/api/ops/work-orders/${no}/complete`, x),
  rejectWorkOrder: (no, reason) => client.post(`/api/ops/work-orders/${no}/reject`, { reason }),
  reworkWorkOrder: (no, reason) => client.post(`/api/ops/work-orders/${no}/rework`, { reason }),

  // 工单扩展
  listSlaRules: (q?: PageQ) => client.get("/api/ops/sla-rules", q),
  listInspectionPlans: (q?: PageQ) => client.get("/api/ops/inspection-plans", q),
  saveSlaRule: (x) => client.post(x.slaNo ? `/api/ops/sla-rules/${x.slaNo}` : "/api/ops/sla-rules", x),
  saveInspectionPlan: (x) => client.post(x.planNo ? `/api/ops/inspection-plans/${x.planNo}` : "/api/ops/inspection-plans", x),
  // ⚠️ S7 后端缺口：巡检计划的「立即执行一次」后端**没有任何端点**（WoExtController 只有
  // inspection-plans 的 list/get/create/update，无 run，也没有定时任务）。USE_MOCK=0 时必然 404。
  // 端点名按现有 `/{no}/{action}` 约定先占位；后端补齐时必须同时实现幂等：
  // 同 planNo + 同周期键的第二次请求要拒绝（幂等键由服务端按 frequency 算，不能信前端传）。
  runInspectionPlan: (no) => client.post(`/api/ops/inspection-plans/${no}/run`),
};
