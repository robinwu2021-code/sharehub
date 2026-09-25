// 覆盖范围：工单开单 + 全状态流转（G6 闭环）、SLA 规则、巡检计划。
//
// 状态机（前端表在 lib/types/workorder.ts 的 WO_TRANSITIONS，**与后端 WoStateMachine 同一张表**）：
//   CREATED --dispatch--> DISPATCHED --accept--> ACCEPTED --process--> PROCESSING
//     --complete--> DONE --close--> CLOSED
//   DISPATCHED / ACCEPTED / PROCESSING --reject--> CREATED（退回重派）
//   PROCESSING --process--> PROCESSING（提交处理进展，只留痕；后端对这条显式跳过状态机）
//   DONE --rework--> PROCESSING（验收不合格退回返工）
// ⚠️ 原先这里写着「唯一权威定义在前端，后端须一致」，而实际两边早就不一致了：
//    后端 ACCEPT 落 ACCEPTED，前端写的是 →PROCESSING —— 真后端接完单，界面上一个按钮都没有。
//    谁是真源不由注释宣布，由卡口比对（backend WorkOrderStateMachineParityTest）。
// 非法迁移必须由服务端拒绝（mock 抛 WorkOrderTransitionError），前端按钮只是「不给点」而非唯一防线。
import type { PageQ, WoQ, WoPoolQ, WoCostQ } from "../query";
import type {
  PageResult, WorkOrder, WorkOrderDraft, WorkOrderHandlePayload, WorkOrderClosePayload,
  SlaRule, InspectionPlan, InspectionRunResult,
  WoSummary, WorkOrderDetail, AssigneeCandidate, WoDeriveReq, WoTakeoverReq, CostRow,
} from "../../types";

export interface WorkOrderApi {
  /** 列表。筛选含运营维度（priority/source/siteNo/assigneeNo/slaState/reviewStatus），行带 `ops`。 */
  listWorkOrders(q?: WoQ): Promise<PageResult<WorkOrder>>;
  /** 开单（workorder:wo:create）。 */
  createWorkOrder(x: WorkOrderDraft): Promise<WorkOrder>;
  /** 派单（workorder:wo:dispatch）：CREATED → DISPATCHED。 */
  dispatchWorkOrder(woNo: string, assignee: string): Promise<WorkOrder>;
  /** 接单（workorder:wo:handle）：DISPATCHED → PROCESSING。 */
  acceptWorkOrder(woNo: string, handler?: string): Promise<WorkOrder>;
  /** 提交处理结果（workorder:wo:handle）：PROCESSING 内留痕，不改状态。 */
  processWorkOrder(woNo: string, x: WorkOrderHandlePayload): Promise<WorkOrder>;
  /**
   * 完工（workorder:wo:handle）：PROCESSING → DONE。按类型收紧（见 woCompleteRules）：
   * 维修 / 装机 / 撤机要照片，维修要故障原因，撤机要清点数。告警来源的单完工后自动复核。
   */
  completeWorkOrder(woNo: string, x: WorkOrderHandlePayload): Promise<WorkOrder>;
  /** 验收关单（workorder:wo:close）：DONE → CLOSED，验收结论必填；复核未通过时验收说明必填。 */
  closeWorkOrder(woNo: string, x: WorkOrderClosePayload): Promise<WorkOrder>;
  /** 驳回退回（workorder:wo:dispatch）：DISPATCHED/PROCESSING → CREATED，原因必填。 */
  rejectWorkOrder(woNo: string, reason: string): Promise<WorkOrder>;
  /** 验收不合格退回返工（DONE → PROCESSING，处理人不变） */
  reworkWorkOrder(woNo: string, reason: string): Promise<WorkOrder>;

  // === 工单扩展 tab ===
  listSlaRules(q?: PageQ): Promise<PageResult<SlaRule>>;
  listInspectionPlans(q?: PageQ): Promise<PageResult<InspectionPlan>>;
  /** 单条 SLA 规则（编辑前取最新，避免拿列表里的旧行覆盖别人刚改的时限）。 */
  getSlaRule(slaNo: string): Promise<SlaRule>;
  /** 单个巡检计划（工单来源是「巡检计划」时，详情里带出路线与负责人）。 */
  getInspectionPlan(planNo: string): Promise<InspectionPlan>;
  saveSlaRule(x: Partial<SlaRule> & { slaNo?: string }): Promise<SlaRule>;
  saveInspectionPlan(x: Partial<InspectionPlan> & { planNo?: string }): Promise<InspectionPlan>;
  /**
   * 巡检计划「立即执行一次」（workorder:inspection:update + workorder:wo:create）：
   * 按路线生成 INSPECT 工单（source=PLAN，sourceNo=planNo）并派给计划负责人。
   * **幂等**：同计划同周期只允许一次，服务端必须按周期键拒绝重复提交。
   * ⚠️ 后端缺口：`WoExtController` 目前没有这个端点（详见 https/workorder.ts）。
   */
  runInspectionPlan(planNo: string): Promise<InspectionRunResult>;

  // ——— 工单增强（2026-09-25）———

  /** 摘要条：待派单 / 即将超时 / 已超时 / 验收不通过。四个都是要人动手的事。 */
  woSummary(): Promise<WoSummary>;
  /** 详情：工单 + 时间线 + 现场照片 + **关联告警**。 */
  getWorkOrderDetail(woNo: string): Promise<WorkOrderDetail>;
  /**
   * 派单候选人。`siteNo` 给了就把该站的运维责任人排前面。
   * 此前派单抽屉用的是写死的 STAFF 常量 —— 运营得自己记「哪个站归谁」，
   * 记错了单子就派到另一个城市。
   */
  assigneeCandidates(siteNo?: string): Promise<AssigneeCandidate[]>;

  /** 派生子单：现场发现的新问题另开一张，而不是塞进当前单的备注里。 */
  deriveWorkOrder(woNo: string, req: WoDeriveReq): Promise<WorkOrder>;
  /**
   * 平台接管（workorder:wo:dispatch，F4）：**代理承接、SLA 已超时**的单改派平台员工。
   * 不满足的后端 409；界面按 woTakeoverBlocked 禁用并说明原因。
   */
  takeoverWorkOrder(woNo: string, req: WoTakeoverReq): Promise<WorkOrder>;

  /** 抢单池（workorder:wo:handle，E3）：未派出的工单，按数据范围 —— 区域员工看到本区域的池。 */
  listWoPool(q?: WoPoolQ): Promise<PageResult<WorkOrder>>;
  /** 抢单（workorder:wo:handle）：派给自己并接单（CREATED → ACCEPTED）。并发两人抢只一人成功。 */
  grabWorkOrder(woNo: string): Promise<WorkOrder>;
  /** 工单成本汇总（workorder:wo:read，G4）：按承担方（站点 / 代理）聚合完工单的配件 + 人工金额。 */
  listWoCosts(q?: WoCostQ): Promise<CostRow[]>;
}
