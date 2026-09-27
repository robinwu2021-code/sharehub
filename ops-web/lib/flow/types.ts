// 流程与工单平台（OpsKit）的前端契约类型 —— **与后端出参同形**。
//
// 真源：docs/technical/v4/16-工作流与工单/21-设计方案-重梳/06-接口设计.md
//   §二 通用约定（基址 /api/opskit/v1，经宿主同源反代；运行时资源在 /workspaces/{ws}/ 下）
//   §七 列表与详情出参（TodoItem · TicketRow · TicketView）—— 2026-09-27 与后端会话逐条对齐
// 现在由 lib/flow/mock-db.ts 实现；对接 opskit-svc 时只换 lib/flow/client.ts，页面不动。
//
// 页面**不传操作人、不传数据范围**：服务从宿主令牌解析（21/06 §一「成员」调用方）。
// 名称类字段一律用服务给的 `name`（已按 Accept-Language 选好），前端不查字典、不内置业务词：
//   办理人类型（代理/员工/客服）来自成员属性 assignee.attrs；工单来源来自类型字段 fields.source。
//
// 2026-09-27 前端提出的 4 项补充（行内 actions、行内 sla、动作 toCategory、按成员属性筛选）
// 后端已全部接受并写进 21/06 §七。

export type TodoOrigin = "PROCESS" | "TICKET";
export type TodoKind = "WORK" | "APPROVAL";
export type StatusCategory = "NEW" | "IN_PROGRESS" | "WAITING" | "DONE" | "CANCELLED";
export type SlaState = "RUNNING" | "WARNING" | "BREACHED" | "MET" | "PAUSED";

/** 代号 + 显示名（+ 排序序数）：名称类字段的统一形状 */
export interface Named { code: string; name: string }
export interface Priority extends Named { ordinal: number }

export interface SubjectRef {
  type: string;
  ref: string;
  display: { title: string; summary?: string };
}

/** SLA 摘要：剩余比例由 startedAtMs / dueAtMs / pausedMs 三项算，前端不猜起点 */
export interface SlaSummary {
  state: SlaState;
  startedAtMs: number;
  dueAtMs: number;
  pausedMs: number;
}

/**
 * 成员属性里的一项（租户自定义，如 partyType）。name 来自空间的「成员属性定义」（后端 21/07 O1-12，
 * 未实现前只给 value）—— **没有 name 就显示原值**，前端不内置「代理 / 员工」这类词。
 */
export interface MemberAttr { value: string; name?: string }

export interface Member {
  ref: string;
  displayName: string;
  attrs?: Record<string, MemberAttr>;
}

/**
 * 类型字段的值：选项 / 成员 / 组带 name；文本、数字只有 value；日期时间的 value 是毫秒；
 * 外部引用的 value 是 {type, ref, display}。字段代号由租户在工单类型里定义 —— 通用渲染要按类型的
 * 字段配置来，不要假定代号（ShareHub 的类型定义了 source 与 sourceRef，工作台的「来源」列用的就是它们）。
 */
export interface FieldValue { value: unknown; name?: string }

// ── 待办 ────────────────────────────────────────────────────────────────
export interface TodoItem {
  taskNo: string;
  kind: TodoKind;
  origin: TodoOrigin;
  title: string;
  subject: SubjectRef;
  priority: Priority;
  createdAtMs: number;
  dueAtMs?: number;
  sla?: SlaSummary;
  /** 可领取（在池里），而不是已派给我 */
  claimable: boolean;
  ticketNo?: string;
  ticketType?: Named;
  approvalNo?: string;
}

export interface TodoQuery {
  origin: "ALL" | TodoOrigin;
  /** true = 只看可抢；false = 只看已派给我的；不传 = 两者都要 */
  claimable?: boolean;
  page: number;
  size: number;
}

/** 待办角标：与待办中心的四个页签一一对应（todos/count） */
export interface TodoCount {
  all: number;
  process: number;
  ticket: number;
  claimable: number;
}

// ── 工单 ────────────────────────────────────────────────────────────────
/** 当前操作人可走的迁移（服务算，前端只渲染） */
export interface TicketAction {
  code: string;
  name: string;
  requires: { fields: string[]; comment: boolean };
  /** 迁移后的状态大类（由流程定义推出）：前端据此把「推进到 DONE」的动作画成主按钮 */
  toCategory: StatusCategory;
}

export interface TicketRow {
  ticketNo: string;
  type: Named;
  title: string;
  subject?: SubjectRef;
  priority: Priority;
  status: Named;
  statusCategory: StatusCategory;
  assignee?: Member;
  requester?: Pick<Member, "ref" | "displayName">;
  slaState: SlaState;
  slaNextDueAtMs?: number;
  escalated: boolean;
  createdAtMs: number;
  updatedAtMs: number;
  /** 列表配置里要显示的可筛选字段（如 source 来源、sourceRef 来源单号） */
  fields: Record<string, FieldValue>;
  /** 与 TodoItem 同形的 SLA 摘要（到期最近的运行中指标）；没有运行中指标时不给，只显示文字 */
  sla?: SlaSummary;
  /** 行内快捷动作：与详情同形、全量（只含当前操作人可走的迁移） */
  actions: TicketAction[];
}

/** 工单查询（21/06 tickets/query 中界面上有筛选控件的那部分） */
export interface TicketQuery {
  typeCode?: string[];
  statusCategory?: StatusCategory[];
  slaState?: SlaState[];
  /**
   * 按成员属性筛受理方（如 partyType=AGENT）。服务先取匹配成员再 IN 过滤；
   * 匹配超过 5000 人返回 422 opskit.ticket.filter_too_broad —— 提示改用组筛选。
   */
  assigneeAttr?: { key: string; values: string[] };
  keyword?: string;
  /** 关闭时间下界（「今日关闭」统计卡的口径） */
  closedFromMs?: number;
  page: number;
  size: number;
}

export type TicketFilter = Omit<TicketQuery, "page" | "size">;

/** 统计卡（ticket-stats/summary）：每个数字附 queryParams，原样回填 query，保证卡与列表同口径 */
export interface TicketStat {
  key: string;
  label: string;
  value: number;
  sub?: string;
  tone?: "warning" | "danger";
  queryParams: TicketFilter;
}

export interface Page<T> {
  list: T[];
  total: number;
}

// ── 工单详情（21/06 §七 TicketView）──────────────────────────────────────
export interface SlaMetric extends SlaSummary {
  code: string;
  name: string;
}

export interface TicketView extends TicketRow {
  description?: string;
  /** 全部字段（详情用）；显示顺序与标签来自类型的字段配置 —— mock 直接给 label */
  fieldList: { code: string; label: string; value: FieldValue }[];
  participants: { role: Named; member: Pick<Member, "ref" | "displayName"> }[];
  /** 每个 SLA 指标一项（响应、解决…） */
  slaMetrics: SlaMetric[];
  resolution?: Named;
  mergedInto?: string;
  /** 去重：并入本单的重复次数与去重键 */
  dedupe?: { key: string; attachedCount: number };
}

/** 时间线（GET /tickets/{no}/timeline）：迁移、派单、SLA 事件、评论同一条流水 */
export interface TimelineEvent {
  seq: number;
  atMs: number;
  kind: "CREATED" | "ASSIGNED" | "TRANSITION" | "SLA" | "DEDUPE" | "COMMENT" | "ESCALATED";
  title: string;
  actor: string;
  detail?: string;
  /** 需要强调的事件（如 SLA 提醒） */
  hot?: boolean;
}

// ── 审批（21/06 §四）─────────────────────────────────────────────────────
export type ApprovalStatus = "PENDING" | "APPROVED" | "REJECTED" | "WITHDRAWN";

export interface ApprovalRow {
  approvalNo: string;
  process: Named;
  subject: SubjectRef;
  status: ApprovalStatus;
  currentStep?: Named;
  currentRound: number;
  requestedBy: Pick<Member, "ref" | "displayName">;
  requestedAtMs: number;
  finishedAtMs?: number;
  /** 当前步开始时刻（待审时「已等待」= 现在 − 它） */
  stepStartedAtMs?: number;
  stepDueAtMs?: number;
}

export interface ApprovalQuery {
  status?: ApprovalStatus[];
  processCode?: string;
  requestedBy?: string;
  fromMs?: number;
  keyword?: string;
  page: number;
  size: number;
}

export interface ApprovalStep {
  code: string;
  name: string;
  /** 审批人说明（如「持 location:contract:audit」） */
  approver: string;
  /** 条件步的进入条件说明；无则必经 */
  condition?: string;
  state: "DONE" | "CURRENT" | "TODO" | "SKIPPED";
  decidedBy?: string;
  decidedAtMs?: number;
}

export interface ApprovalRound {
  round: number;
  status: ApprovalStatus | "IN_PROGRESS";
  atMs: number;
  by: string;
  comment?: string;
}

export interface ApprovalView extends ApprovalRow {
  /** 业务对象的关键字段（由业务系统提交时给出的展示字段） */
  facts: { label: string; value: string; hint?: string }[];
  steps: ApprovalStep[];
  rounds: ApprovalRound[];
  evidence: { name: string; count?: number }[];
  /** 我在这张单上能做什么（服务按规则算） */
  mine: { taskNo?: string; canDecide: boolean; whyNot?: string; canWithdraw: boolean };
  /** 这条流程开着的内建规则（界面上的「规则提示」） */
  rules: { crossStepExclusive: boolean };
}

// ── 审核队列（21/06 §四 任务）─────────────────────────────────────────────
export interface QueueSummary extends Named { unclaimed: number }

export interface QueueTask {
  taskNo: string;
  queue: Named;
  subject: SubjectRef;
  priority: Priority;
  createdAtMs: number;
  evidence: string;
  claimedBy?: Pick<Member, "ref" | "displayName">;
  /** 领取后自动放回的时刻 */
  claimExpiresAtMs?: number;
}

// ── 流程定义（21/06 §四：PUT …/processes/{code} 幂等发布，校验失败 422 逐条）─────────
/** 审批人只能选四种（原型 P5a「改动的边界」） */
export type ApproverKind = "PERM" | "ROLE" | "QUEUE" | "ASSIGNED";
/** 进入条件只支持「属性为真 / 等于 / 属于」；复杂判断由业务系统提交前算成属性 */
export type ConditionOp = "TRUE" | "EQ" | "IN";

export interface ProcessStepDef {
  code: string;
  name: string;
  approver: { kind: ApproverKind; value: string };
  /** ANY = 或签（一人通过即过）；ALL = 会签 */
  mode: "ANY" | "ALL";
  when?: { field: string; op: ConditionOp; value?: string };
  timeLimitHours: number;
}

export interface ProcessDef {
  code: string;
  name: string;
  steps: ProcessStepDef[];
  /** 可关的规则只有跨步互斥；「提交人不能审自己」「驳回必填原因」恒开 */
  crossStepExclusive: boolean;
}

export interface ProcessSummary {
  code: string;
  name: string;
  stepsSummary: string;
  version: string;
  /** 在途（待审）单数 */
  running: number;
  enabled: boolean;
}

export interface ProcessVersion {
  version: string;
  atMs: number;
  channel: "API" | "CONSOLE";
  by: string;
  note?: string;
  current: boolean;
}

export interface PublishResult {
  version: string;
  /** false = 内容没变，没有生成新版本（幂等） */
  created: boolean;
  /** 还在按旧版本走的在途单数 */
  runningOnOlderVersions: number;
}

/** 422 的逐条错误（21/06 §二 错误形状里的 errors[]） */
export interface FieldError { path: string; error: string }

// ── 运营配置 ────────────────────────────────────────────────────────────
export interface SlaPolicy {
  code: string;
  ticketType: Named;
  /** 优先级代号；"*" = 其余 */
  priority: string;
  respondMin: number;
  resolveMin: number;
  /** true = 按营业时间计（用 calendar）；false = 7×24 */
  businessHours: boolean;
  calendar?: string;
}

export interface WorkCalendar {
  code: string;
  name: string;
  timeZone: string;
  /** 1–7，周一为 1 */
  weekdays: number[];
  open: string;
  close: string;
  holidays: { date: string; name: string }[];
}

export interface QueueGroup {
  code: string;
  name: string;
  sort: "PRIORITY_THEN_AGE" | "AGE";
  claimTimeoutMin: number;
}

// ── 接入管理（21/06 §三：应用 · 订阅 · 投递）──────────────────────────────────
export interface AppRow {
  id: string;
  code: string;
  name: string;
  callbackUrl: string;
  timeZone: string;
  enabled: boolean;
}

export interface DeliveryRow {
  id: string;
  eventType: string;
  app: string;
  idemKey: string;
  status: "DELIVERED" | "FAILED" | "RETRYING";
  httpStatus?: number;
  error?: string;
  durationMs?: number;
  attempts: number;
  maxAttempts: number;
  atMs: number;
}

export interface DefinitionVersionRow {
  kind: "PROCESS" | "TICKET_TYPE" | "STATE_MACHINE";
  code: string;
  version: string;
  atMs: number;
  by: string;
}
