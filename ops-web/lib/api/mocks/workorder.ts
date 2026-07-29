// 覆盖范围：工单列表与派单、SLA 规则、巡检计划。
import * as db from "../../mock/db";
import type { WorkOrderApi } from "../contracts/workorder";
import type { PageQ, WoQ } from "../query";
import { wait } from "./_wait";

export const workOrderMock: WorkOrderApi = {
  listWorkOrders: (q: WoQ = {}) =>
    wait(db.paginate(db.workOrders, q.page, q.size, (w) =>
      db.kwHit(q.keyword, w.woNo, w.cabinetNo) && (!q.status || w.status === q.status) && (!q.type || w.type === q.type))),
  dispatchWorkOrder: (no, assignee) => {
    const w = db.workOrders.find((x) => x.woNo === no);
    if (w) { w.assigneeName = assignee; w.status = "DISPATCHED"; }
    return wait({ ok: true } as const, 400);
  },

  // 工单扩展
  listSlaRules: (q: PageQ = {}) => wait(db.listSlaRules(q)),
  listInspectionPlans: (q: PageQ = {}) => wait(db.listInspectionPlans(q)),
  saveSlaRule: (x) => wait(db.saveSlaRule(x), 350),
  saveInspectionPlan: (x) => wait(db.saveInspectionPlan(x), 350),
};
