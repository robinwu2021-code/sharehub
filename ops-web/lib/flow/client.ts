// 流程与工单平台的客户端 —— 页面唯一的取数入口。
//
// 两份实现、同一形状：mockFlow（浏览器内 mock）与 httpFlow（opskit-svc，见 lib/flow/http.ts），
// 构建时 NEXT_PUBLIC_FLOW_API=http 选后者。httpFlow 标注为 `typeof mockFlow`，两边形状不一致编译不过。
// 方法名、参数、出参与 21/06 接口设计一一对应，注释里标了对应的端点。
// 基址：经宿主同源反代 /api/opskit/v1；运行时资源在 /workspaces/{ws}/ 下，ShareHub 场景 ws = ext:{租户号}。
// 页面不直接 import mock-db（architecture: 页面只认 client）。
import * as db from "./mock-db";
import * as ap from "./mock-approvals";
import * as cfg from "./mock-config";
import { FLOW_USE_HTTP, opskit, ws } from "./http";
import type {
  AppRow, ApprovalTemplate, DefinitionVersionRow, DeliveryRow, PublishResult, QueueGroup, TemplateSummary, TemplateVersion,
  SlaPolicy, WorkCalendar,
  ApprovalQuery, ApprovalRow, ApprovalStatus, ApprovalView, Member, Named, Page, QueueCount, QueueTask, TicketQuery,
  ReassignResult, TicketRow, TicketStat, TicketView, TimelineEvent, TodoCount, TodoItem, TodoQuery,
} from "./types";

/** 模拟网络往返：让加载态、按钮 loading 在 mock 下也真实出现 */
const tick = <T,>(f: () => T) => new Promise<T>((resolve, reject) => {
  setTimeout(() => { try { resolve(f()); } catch (e) { reject(e); } }, 120);
});

const mockFlow = {
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
  reassign: (nos: string[], to: Member, reason: string): Promise<ReassignResult> =>
    tick(() => db.reassignTickets(nos, to, reason)),
  // ── 选项（下拉）：一律异步，走 lib/flow/options.ts 的钩子 ──
  /** POST /workspaces/{ws}/members/query：改派候选 / 提交人筛选。给 total —— 超出一页时页面要说「只列了前 N 人」，不静默截断 */
  members: (): Promise<Page<Member>> => tick(() => ({ list: db.ASSIGNEE_OPTIONS, total: db.ASSIGNEE_OPTIONS.length })),
  /** GET /workspaces/{ws}/ticket-types（名称已按语言选好） */
  ticketTypes: (): Promise<Named[]> => tick(() => db.TICKET_TYPES),
  /** 成员属性 partyType 的取值与显示名（21/07 O1-12 成员属性定义） */
  partyOptions: (): Promise<Named[]> => tick(() => db.PARTY_OPTIONS),

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
  /** 审批列表的流程筛选项 = 审批模板列表（代号 + 已选语种的名称） */
  processOptions: (): Promise<Named[]> => tick(() => ap.PROCESS_OPTIONS),
  /** 提交人筛选项（成员查询；mock 另含「系统」这类非成员提交方） */
  requesterOptions: (): Promise<Named[]> => tick(() => ap.REQUESTER_OPTIONS),

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

// ── 真实服务 ─────────────────────────────────────────────────────────────
// ⚠️ 标「待对齐」的是契约里没写死请求体的，首次联调时按后端实际核对（22/README 联调记录）。
const ACCOUNT = process.env.NEXT_PUBLIC_OPSKIT_ACCOUNT || "";
const acct = (path: string) => `/accounts/${encodeURIComponent(ACCOUNT)}${path}`;
const enc = encodeURIComponent;
const POST = <T,>(path: string, body: unknown = {}) => opskit<T>("POST", path, body);
const GET = <T,>(path: string) => opskit<T>("GET", path);
const PUT = <T,>(path: string, body: unknown) => opskit<T>("PUT", path, body);

const httpFlow: typeof mockFlow = {
  todos: (q) => POST(ws("/todos/query"), q),
  todoCount: () => POST(ws("/todos/count"), {}),
  tickets: (q) => POST(ws("/tickets/query"), q),
  ticketStats: () => POST(ws("/ticket-stats/summary"), {}),
  claim: (no) => POST(ws(`/tickets/${enc(no)}/claim`)),
  transition: (no, code, comment) => POST(ws(`/tickets/${enc(no)}/transitions/${enc(code)}`), comment ? { comment } : {}),
  ticket: (no) => GET(ws(`/tickets/${enc(no)}`)),
  timeline: (no) => GET(ws(`/tickets/${enc(no)}/timeline`)),
  comment: async (no, body) => {
    await POST(ws(`/tickets/${enc(no)}/comments`), { body, visibility: "INTERNAL" });
    return GET(ws(`/tickets/${enc(no)}/timeline`));
  },
  assign: (no, to, reason) => POST(ws(`/tickets/${enc(no)}/assign`), { to: `member:${to.ref}`, reason }),
  pool: (no, reason) => POST(ws(`/tickets/${enc(no)}/pool`), { reason }),
  /** 勾选式：{ticketNos[≤200], to, reason}（另一式按原受理人 {from, …} 界面不用）；逐单结果 */
  reassign: (nos, to, reason) => POST(ws("/tickets/reassign"), { ticketNos: nos, to: `member:${to.ref}`, reason }),
  members: async () => {
    const p = await POST<Page<{ memberId: string; displayName: string; attrs?: Member["attrs"] }>>(ws("/members/query"), { page: 1, size: 200 });
    return { ...p, list: p.list.map(({ memberId, ...m }) => ({ ref: memberId, ...m })) };
  },
  ticketTypes: () => GET(ws("/ticket-types")),
  /** GET /member-attributes 给全部属性定义，取 partyType 的取值与显示名 */
  partyOptions: async () => ((await GET<{ key: string; values: { value: string; name?: string }[] }[]>(ws("/member-attributes")))
    .find((a) => a.key === "partyType")?.values ?? []).map((v) => ({ code: v.value, name: v.name ?? v.value })),

  approvals: ({ fromMs, ...q }) => POST(ws("/approvals/query"), { ...q, ...(fromMs ? { from: fromMs } : {}) }),
  approvalCount: () => POST(ws("/approvals/count"), {}),
  approval: (no) => GET(ws(`/approvals/${enc(no)}`)),
  decide: (no, taskNo, decision, reason) => POST(ws(`/approvals/${enc(no)}/decide`), { taskNo, decision, ...(reason ? { reason } : {}) }),
  withdraw: (no) => POST(ws(`/approvals/${enc(no)}/withdraw`)),
  processOptions: async () => (await GET<{ code: string; name: string }[]>(ws("/approval-templates"))).map(({ code, name }) => ({ code, name })),
  requesterOptions: async () => (await httpFlow.members()).list.map((m) => ({ code: m.ref, name: m.displayName })),

  queueCounts: () => POST(ws("/tasks/queue-counts"), {}),
  /** {queueGroup?, status?[], claimed?, assignee?: "me" | memberId}（后端 9-28 确认） */
  queueTasks: (queue, page, size) => POST(ws("/tasks/query"), { queueGroup: queue, status: ["OPEN"], claimed: false, page, size }),
  myClaimed: async () => (await POST<{ list: Awaited<ReturnType<typeof mockFlow.myClaimed>> }>(ws("/tasks/query"), { assignee: "me", claimed: true, status: ["OPEN"], page: 1, size: 200 })).list,
  claimTask: (taskNo) => POST(ws(`/tasks/${enc(taskNo)}/claim`)),
  releaseTask: (taskNo) => POST(ws(`/tasks/${enc(taskNo)}/release`)),
  completeTask: (taskNo, decision, reason) => POST(ws(`/tasks/${enc(taskNo)}/complete`), { decision, ...(reason ? { reason } : {}) }),

  templates: () => GET(ws("/approval-templates")),
  template: (code) => GET(ws(`/approval-templates/${enc(code)}`)),
  templateVersions: (code) => GET(ws(`/approval-templates/${enc(code)}/versions`)),
  publishTemplate: (def, note) => PUT(ws(`/approval-templates/${enc(def.code)}`), { ...def, ...(note ? { note } : {}) }),
  setTemplateEnabled: (code, enabled) => POST(ws(`/approval-templates/${enc(code)}/${enabled ? "enable" : "disable"}`)),

  slaPolicies: () => GET(ws("/sla-policies")),
  saveSlaPolicy: (p) => PUT(ws(`/sla-policies/${enc(p.code)}`), p),
  calendars: () => GET(ws("/calendars")),
  saveCalendar: (c) => PUT(ws(`/calendars/${enc(c.code)}`), c),
  queueGroups: () => GET(ws("/groups?kind=QUEUE")),
  saveQueueGroup: (q) => PUT(ws(`/groups/${enc(q.code)}`), q),

  apps: () => GET(acct("/apps")),
  createApp: (a) => POST(acct("/apps"), a),
  rotateCredential: (id) => POST(`/apps/${enc(id)}/rotate-credential`),
  deliveries: async () => (await POST<{ list: Awaited<ReturnType<typeof mockFlow.deliveries>> }>(acct("/webhook-deliveries/query"), { page: 1, size: 200 })).list,
  redeliver: (id) => POST(`/webhook-deliveries/${enc(id)}/redeliver`),
  definitionVersions: () => GET(acct(`/definitions?workspace=${enc(process.env.NEXT_PUBLIC_OPSKIT_WS || "")}`)),
};

export const flow = FLOW_USE_HTTP ? httpFlow : mockFlow;

/** react-query 的 key 前缀：任何写操作后整体失效，待办角标、统计卡、列表一起刷新 */
export const FLOW_KEY = ["flow"] as const;
