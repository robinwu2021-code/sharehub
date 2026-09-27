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
// 标 ⏳ 的字段是**前端已向后端提出、待确认**的补充（见 22-前端方案 README §二）；
// 后端确认前，页面对它们一律做缺省处理（没有就不画 / 不显示），不会因为缺字段而报错。

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

/** 成员属性里的一项（租户自定义，如 partyType）。⏳ 形状待后端确认：是否同时给显示名 */
export interface MemberAttr { value: string; name?: string }

export interface Member {
  ref: string;
  displayName: string;
  attrs?: Record<string, MemberAttr>;
}

/** 类型字段的值（选项集字段带显示名） */
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
  /** ⏳ 迁移后的状态大类：前端据此把「推进到 DONE」的动作画成主按钮 */
  toCategory?: StatusCategory;
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
  /** ⏳ 与 TodoItem 同形的 SLA 摘要：画「剩余比例」色条要用；没有就只显示文字 */
  sla?: SlaSummary;
  /** ⏳ 行内快捷动作（原型工作台每行都有）；没有就不出按钮，进详情办理 */
  actions?: TicketAction[];
}

/** 工单查询（21/06 tickets/query 中界面上有筛选控件的那部分） */
export interface TicketQuery {
  typeCode?: string[];
  statusCategory?: StatusCategory[];
  slaState?: SlaState[];
  /** ⏳ 按成员属性筛受理方（如 partyType=AGENT）；原型「受理方：全部」 */
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
