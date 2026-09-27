// 流程与工单平台的客户端 —— 页面唯一的取数入口。
//
// 现在转给 mock（lib/flow/mock-db.ts）；对接 opskit-svc 时**只改这个文件**：
// 方法名、参数、出参与 21/06 接口设计一一对应，注释里标了对应的端点。
// 基址：经宿主同源反代 /api/opskit/v1；运行时资源在 /workspaces/{ws}/ 下，ShareHub 场景 ws = ext:{租户号}。
// 页面不直接 import mock-db（architecture: 页面只认 client）。
import * as db from "./mock-db";
import * as ap from "./mock-approvals";
import * as cfg from "./mock-config";
import type {
  AppRow, ApprovalTemplate, DefinitionVersionRow, DeliveryRow, PublishResult, QueueGroup, TemplateSummary, TemplateVersion,
  SlaPolicy, WorkCalendar,
  ApprovalQuery, ApprovalRow, ApprovalStatus, ApprovalView, Member, Named, Page, QueueCount, QueueTask, TicketQuery,
  TicketRow, TicketStat, TicketView, TimelineEvent, TodoCount, TodoItem, TodoQuery,
} from "./types";

/** 模拟网络往返：让加载态、按钮 loading 在 mock 下也真实出现 */
const tick = <T,>(f: () => T) => new Promise<T>((resolve, reject) => {
  setTimeout(() => { try { resolve(f()); } catch (e) { reject(e); } }, 120);
});

export const flow = {
  /** POST /workspaces/{ws}/todos/query */
  todos: (q: TodoQuery): Promise<Page<TodoItem>> => tick(() => db.queryTodos(q)),
  /** POST /workspaces/{ws}/todos/count */
  todoCount: (): Promise<TodoCount> => tick(() => db.countTodos()),
  /** POST /workspaces/{ws}/tickets/query */
  tickets: (q: TicketQuery): Promise<Page<TicketRow>> => tick(() => db.queryTickets(q)),
  /** POST /workspaces/{ws}/ticket-stats/summary */
  ticketStats: (): Promise<TicketStat[]> => tick(() => db.ticketStats()),
  /** POST /workspaces/{ws}/tickets/{no}/claim */
  claim: (no: string): Promise<TicketRow> => tick(() => db.claimTicket(no)),
  /** POST /workspaces/{ws}/tickets/{no}/transitions/{code} */
  transition: (no: string, code: string, comment?: string): Promise<TicketRow> => tick(() => db.transitionTicket(no, code, Date.now(), comment)),
  /** GET /workspaces/{ws}/tickets/{no} */
  ticket: (no: string): Promise<TicketView> => tick(() => db.getTicketView(no)),
  /** GET /workspaces/{ws}/tickets/{no}/timeline */
  timeline: (no: string): Promise<TimelineEvent[]> => tick(() => db.getTimeline(no)),
  /** POST /workspaces/{ws}/tickets/{no}/comments */
  comment: (no: string, body: string): Promise<TimelineEvent[]> => tick(() => db.addComment(no, body)),
  /** POST /workspaces/{ws}/tickets/{no}/assign {to, reason}（to 必填） */
  assign: (no: string, to: Member, reason: string): Promise<TicketRow> => tick(() => db.assignTicket(no, to, reason)),
  /** POST /workspaces/{ws}/tickets/{no}/pool {reason}：退回抢单池 */
  pool: (no: string, reason: string): Promise<TicketRow> => tick(() => db.poolTicket(no, reason)),
  /** POST /workspaces/{ws}/tickets/reassign */
  reassign: (nos: string[], to: Member, reason: string): Promise<TicketRow[]> =>
    tick(() => db.reassignTickets(nos, to, reason)),
  /** 改派候选（接后端后：成员 / 组接口） */
  assigneeOptions: (): Member[] => db.ASSIGNEE_OPTIONS,
  /** 工单类型（接后端后：GET /workspaces/{ws}/ticket-types） */
  ticketTypes: (): Named[] => db.TICKET_TYPES,
  /** 受理方筛选项：成员属性 partyType 的取值（接后端后：成员属性定义） */
  partyOptions: (): Named[] => db.PARTY_OPTIONS,

  // ── 审批 ──
  /** POST /workspaces/{ws}/approvals/query */
  approvals: (q: ApprovalQuery): Promise<Page<ApprovalRow>> => tick(() => ap.queryApprovals(q)),
  /** POST /workspaces/{ws}/approvals/count：按状态分组计数（状态页签角标） */
  approvalCount: (): Promise<Record<ApprovalStatus, number>> => tick(() => ap.countApprovals()),
  /** GET /workspaces/{ws}/approvals/{no} */
  approval: (no: string): Promise<ApprovalView> => tick(() => ap.getApproval(no)),
  /** POST /workspaces/{ws}/approvals/{no}/decide {taskNo, decision, reason?} */
  decide: (no: string, taskNo: string, decision: "APPROVE" | "REJECT", reason?: string): Promise<ApprovalView> =>
    tick(() => ap.decide(no, taskNo, decision, reason)),
  /** POST /workspaces/{ws}/approvals/{no}/withdraw */
  withdraw: (no: string): Promise<ApprovalView> => tick(() => ap.withdraw(no)),
  /** 流程筛选项（GET …/processes） */
  processOptions: (): Named[] => ap.PROCESS_OPTIONS,
  /** 提交人筛选项（成员查询） */
  requesterOptions: (): Named[] => ap.REQUESTER_OPTIONS,

  // ── 审核队列（任务）──
  /** POST /workspaces/{ws}/tasks/queue-counts：每个队列组的待领数与我已领数 */
  queueCounts: (): Promise<QueueCount[]> => tick(() => ap.queueCounts()),
  /** POST /workspaces/{ws}/tasks/query {queueGroup, status: OPEN} */
  queueTasks: (queue: string, page: number, size: number): Promise<Page<QueueTask>> => tick(() => ap.queueTasksOf(queue, page, size)),
  /** POST /workspaces/{ws}/tasks/query {assignee: me, claimed} */
  myClaimed: (): Promise<QueueTask[]> => tick(() => ap.myClaimed()),
  /** POST /workspaces/{ws}/tasks/{no}/claim */
  claimTask: (taskNo: string): Promise<QueueTask> => tick(() => ap.claimTask(taskNo)),
  /** POST /workspaces/{ws}/tasks/{no}/release */
  releaseTask: (taskNo: string): Promise<QueueTask> => tick(() => ap.releaseTask(taskNo)),
  /** POST /workspaces/{ws}/tasks/{no}/complete */
  completeTask: (taskNo: string, decision: "APPROVE" | "REJECT", reason?: string): Promise<void> =>
    tick(() => ap.completeTask(taskNo, decision, reason)),

  // ── 审批模板（控制台编辑的形态，服务端编译成审批类流程）──
  /** GET /workspaces/{ws}/approval-templates（含在途数） */
  templates: (): Promise<TemplateSummary[]> => tick(() => cfg.listTemplates()),
  /** GET /workspaces/{ws}/approval-templates/{code} */
  template: (code: string): Promise<ApprovalTemplate> => tick(() => cfg.getTemplate(code)),
  /** GET /workspaces/{ws}/approval-templates/{code}/versions */
  templateVersions: (code: string): Promise<TemplateVersion[]> => tick(() => cfg.templateVersions(code)),
  /** PUT /workspaces/{ws}/approval-templates/{code}（幂等发布；422 的 errors[].path 按模板字段给） */
  publishTemplate: (def: ApprovalTemplate, note: string, isNew: boolean): Promise<PublishResult> => tick(() => cfg.publishTemplate(def, note, isNew)),
  /** POST …/approval-templates/{code}/disable · /enable */
  setTemplateEnabled: (code: string, enabled: boolean): Promise<void> => tick(() => cfg.setTemplateEnabled(code, enabled)),

  // ── 运营配置 ──
  /** GET …/sla-policies */
  slaPolicies: (): Promise<SlaPolicy[]> => tick(() => cfg.listSlaPolicies()),
  /** PUT …/sla-policies/{code} */
  saveSlaPolicy: (p: SlaPolicy): Promise<SlaPolicy> => tick(() => cfg.upsertSlaPolicy(p)),
  /** GET /workspaces/{ws}/calendars */
  calendars: (): Promise<WorkCalendar[]> => tick(() => cfg.listCalendars()),
  /** PUT /workspaces/{ws}/calendars/{code} */
  saveCalendar: (c: WorkCalendar): Promise<WorkCalendar> => tick(() => cfg.upsertCalendar(c)),
  /** GET /workspaces/{ws}/groups?kind=QUEUE（队列组就是 kind=QUEUE 的组） */
  queueGroups: (): Promise<QueueGroup[]> => tick(() => cfg.listQueueGroups()),
  /** PUT /workspaces/{ws}/groups/{code} */
  saveQueueGroup: (q: QueueGroup, isNew: boolean): Promise<QueueGroup> => tick(() => cfg.upsertQueueGroup(q, isNew)),
  ticketTypeOptions: () => cfg.TICKET_TYPE_OPTIONS,

  // ── 接入 ──
  /** GET /accounts/{a}/apps */
  apps: (): Promise<AppRow[]> => tick(() => cfg.listApps()),
  /** POST /accounts/{a}/apps（凭证只返回这一次） */
  createApp: (a: Omit<AppRow, "id" | "enabled">): Promise<{ app: AppRow; secret: string }> => tick(() => cfg.createApp(a)),
  /** POST /apps/{id}/rotate-credential（旧凭证立即失效） */
  rotateCredential: (id: string): Promise<{ secret: string }> => tick(() => cfg.rotateCredential(id)),
  /** POST /accounts/{a}/webhook-deliveries/query */
  deliveries: (): Promise<DeliveryRow[]> => tick(() => cfg.listDeliveries()),
  /** POST /webhook-deliveries/{id}/redeliver */
  redeliver: (id: string): Promise<DeliveryRow> => tick(() => cfg.redeliver(id)),
  /** GET /accounts/{a}/definitions?workspace=：流程 · 审批模板 · 工单类型 · SLA 策略的当前版本 */
  definitionVersions: (): Promise<DefinitionVersionRow[]> => tick(() => cfg.definitionVersions()),
};

/** react-query 的 key 前缀：任何写操作后整体失效，待办角标、统计卡、列表一起刷新 */
export const FLOW_KEY = ["flow"] as const;
