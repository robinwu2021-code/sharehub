// 覆盖范围：工单开单 + 全状态流转（G6 闭环）、SLA 规则、巡检计划。
//
// 状态机（唯一权威定义在 lib/mock/db/workorder.ts 的 WO_TRANSITIONS，后端须一致）：
//   CREATED --dispatch--> DISPATCHED --accept--> PROCESSING --complete--> DONE --close--> CLOSED
//   DISPATCHED / PROCESSING --reject--> CREATED（退回重派）
//   PROCESSING --process--> PROCESSING（提交处理进展，只留痕）
// 非法迁移必须由服务端拒绝（mock 抛 WorkOrderTransitionError），前端按钮只是「不给点」而非唯一防线。
import type { PageQ, WoQ } from "../query";
import type {
  PageResult, WorkOrder, WorkOrderDraft, WorkOrderHandlePayload, WorkOrderClosePayload,
  SlaRule, InspectionPlan,
} from "../../types";

export interface WorkOrderApi {
  listWorkOrders(q?: WoQ): Promise<PageResult<WorkOrder>>;
  /** 开单（workorder:wo:create）。 */
  createWorkOrder(x: WorkOrderDraft): Promise<WorkOrder>;
  /** 派单（workorder:wo:dispatch）：CREATED → DISPATCHED。 */
  dispatchWorkOrder(woNo: string, assignee: string): Promise<WorkOrder>;
  /** 接单（workorder:wo:handle）：DISPATCHED → PROCESSING。 */
  acceptWorkOrder(woNo: string, handler?: string): Promise<WorkOrder>;
  /** 提交处理结果（workorder:wo:handle）：PROCESSING 内留痕，不改状态。 */
  processWorkOrder(woNo: string, x: WorkOrderHandlePayload): Promise<WorkOrder>;
  /** 完成（workorder:wo:handle）：PROCESSING → DONE。 */
  completeWorkOrder(woNo: string, x: WorkOrderHandlePayload): Promise<WorkOrder>;
  /** 验收关单（workorder:wo:close）：DONE → CLOSED，验收结论必填。 */
  closeWorkOrder(woNo: string, x: WorkOrderClosePayload): Promise<WorkOrder>;
  /** 驳回退回（workorder:wo:dispatch）：DISPATCHED/PROCESSING → CREATED，原因必填。 */
  rejectWorkOrder(woNo: string, reason: string): Promise<WorkOrder>;
  /** 验收不合格退回返工（DONE → PROCESSING，处理人不变） */
  reworkWorkOrder(woNo: string, reason: string): Promise<WorkOrder>;

  // === 工单扩展 tab ===
  listSlaRules(q?: PageQ): Promise<PageResult<SlaRule>>;
  listInspectionPlans(q?: PageQ): Promise<PageResult<InspectionPlan>>;
  saveSlaRule(x: Partial<SlaRule> & { slaNo?: string }): Promise<SlaRule>;
  saveInspectionPlan(x: Partial<InspectionPlan> & { planNo?: string }): Promise<InspectionPlan>;
}
