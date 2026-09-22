// 覆盖范围：工单开单 + 全状态流转（G6 闭环）、SLA 规则、巡检计划。
// 说明：状态机与必填校验一律落在 db 层（lib/mock/db/workorder.ts），本文件只做「延迟 + 透传」，
// 且各动作方法**声明为 async**，让 db 抛的 WorkOrderTransitionError 变成 rejected promise，
// 由页面 MutationCache.onError 统一弹错——不能默默吞掉非法迁移。
import * as db from "../../mock/db";
import * as wo from "../../mock/db/workorder";
import type { WorkOrderApi } from "../contracts/workorder";
import type { PageQ, WoQ } from "../query";
import { wait } from "./_wait";

export const workOrderMock: WorkOrderApi = {
  listWorkOrders: (q: WoQ = {}) => wait(wo.listWorkOrders(q)),
  createWorkOrder: async (x) => wait(wo.createWorkOrder(x), 400),
  dispatchWorkOrder: async (no, assignee) => wait(wo.dispatchWorkOrder(no, assignee), 400),
  acceptWorkOrder: async (no, handler) => wait(wo.acceptWorkOrder(no, handler), 400),
  processWorkOrder: async (no, x) => wait(wo.processWorkOrder(no, x), 400),
  completeWorkOrder: async (no, x) => wait(wo.completeWorkOrder(no, x), 400),
  closeWorkOrder: async (no, x) => wait(wo.closeWorkOrder(no, x), 400),
  rejectWorkOrder: async (no, reason) => wait(wo.rejectWorkOrder(no, reason), 400),
  reworkWorkOrder: async (no, reason) => wait(wo.reworkWorkOrder(no, reason), 400),

  // 工单扩展
  listSlaRules: (q: PageQ = {}) => wait(db.listSlaRules(q)),
  listInspectionPlans: (q: PageQ = {}) => wait(db.listInspectionPlans(q)),
  saveSlaRule: (x) => wait(db.saveSlaRule(x), 350),
  saveInspectionPlan: (x) => wait(db.saveInspectionPlan(x), 350),
  // 立即执行一次：db 层抛 InspectionRunError（停用/本周期已执行/站点无机柜），
  // 声明 async 让它变成 rejected promise 交给全局 onError，不能默默吞掉
  runInspectionPlan: async (no) => wait(wo.runInspectionPlanNow(no), 500),
};
