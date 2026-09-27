// 流程与工单平台的 mock 实现（内存库）。
//
// 纪律同 lib/mock/db（CLAUDE.md）：写操作**真改库**，重新查询能读回；
// **状态机在这一层强制**，非法迁移用 fail() 抛三语业务错误 —— 页面按钮只是体验，闸在这里。
// 对接 opskit-svc 后这一层下线，但它的行为就是对后端的期望：见 mock-db.test.ts。
//
// 出参形状与后端 21/06 §七 同形（TodoItem / TicketRow），业务词（类型名、状态名、办理人类型、来源）
// 都作为「服务给的 name」产出 —— 页面不内置这些词。
//
// 与服务端同构的两点：
//  1. SLA 状态**读时计算**（按截止时间与暂停），不是存死的字段；
//  2. 工单的可走动作 actions[] 由这里按「状态 × SLA × 受理方」算好返回，页面不维护迁移表。
import { fail, notFound } from "@/lib/biz-error";
import type {
  Member, Named, Page, Priority, SlaMetric, SlaState, SlaSummary, StatusCategory, TicketAction, TicketFilter,
  TicketQuery, TicketRow, TicketStat, TicketView, TimelineEvent, TodoCount, TodoItem, TodoQuery,
} from "./types";

const MIN = 60_000;
const HOUR = 60 * MIN;
/** 「即将超时」阈值：两小时内到期（与运营台既有工单口径一致） */
export const DUE_SOON_MS = 2 * HOUR;

/** 成员属性 partyType 的选项（租户自定义；这里是 ShareHub 的三种） */
const PARTY: Record<string, string> = { AGENT: "代理", STAFF: "员工", CS: "客服" };
const member = (party: string, ref: string, displayName: string): Member =>
  ({ ref, displayName, attrs: { partyType: { value: party, name: PARTY[party] } } });

/** mock 的当前操作人。真实环境由服务从宿主令牌解析，前端不传 */
export const ME = member("STAFF", "admin", "admin");

/** 优先级选项集（租户可配；ordinal 0 最急） */
const PRIO: Record<string, Priority> = {
  URGENT: { code: "URGENT", name: "紧急", ordinal: 0 },
  HIGH: { code: "HIGH", name: "高", ordinal: 1 },
  NORMAL: { code: "NORMAL", name: "普通", ordinal: 2 },
  LOW: { code: "LOW", name: "低", ordinal: 3 },
};
type PrioCode = keyof typeof PRIO;

/** 工单类型字段 source 的选项集 */
const SOURCE_OPT: Record<string, string> = { ALARM: "告警", PATROL: "巡检", CUSTOMER: "C 端", MANUAL: "手工" };
type SourceCode = keyof typeof SOURCE_OPT;

// ── 工单类型的状态图（运维类；另两类复用同一张，mock 足够）──────────────────────
type StatusCode = "PENDING_ACCEPT" | "PROCESSING" | "WAITING_EXTERNAL" | "PENDING_REVIEW" | "CLOSED" | "CANCELLED";
const STATUS: Record<StatusCode, { name: string; category: StatusCategory }> = {
  PENDING_ACCEPT: { name: "待接单", category: "NEW" },
  PROCESSING: { name: "处理中", category: "IN_PROGRESS" },
  WAITING_EXTERNAL: { name: "等外部", category: "WAITING" },
  PENDING_REVIEW: { name: "待复核", category: "IN_PROGRESS" },
  CLOSED: { name: "已关闭", category: "DONE" },
  CANCELLED: { name: "已撤销", category: "CANCELLED" },
};
const OPEN: StatusCode[] = ["PENDING_ACCEPT", "PROCESSING", "WAITING_EXTERNAL", "PENDING_REVIEW"];

const TYPE_NAME: Record<string, string> = { ops: "运维", "cs-report": "报障", "after-sales": "售后" };
/** 工单类型清单（接后端后：GET ticket-types） */
export const TICKET_TYPES: Named[] = [
  { code: "ops", name: "运维" }, { code: "cs-report", name: "客服报障" }, { code: "after-sales", name: "售后" },
];

interface TicketRec {
  ticketNo: string;
  typeCode: string;
  title: string;
  subject: TicketRow["subject"] & object;
  priority: PrioCode;
  status: StatusCode;
  assignee: Member | null;
  /** 进了抢单池（未派、够资格的人可领） */
  pooled: boolean;
  slaStartMs: number;
  slaDueAtMs: number;
  /** 暂停中：暂停开始时刻与原因 */
  pausedAtMs: number | null;
  pausedReason?: string;
  source: { kind: SourceCode; ref?: string };
  escalated: boolean;
  nudgedAtMs: number | null;
  description?: string;
  dedupe?: { key: string; attachedCount: number };
  cancelReason?: string;
  createdAtMs: number;
  closedAtMs: number | null;
}

interface ApprovalTodoRec {
  taskNo: string;
  approvalNo: string;
  title: string;
  subject: TodoItem["subject"];
  priority: PrioCode;
  startMs: number;
  dueAtMs: number;
}

let tickets: TicketRec[] = [];
/** 每张单的时间线（只追加） */
let timelines: Record<string, TimelineEvent[]> = {};
let approvalTodos: ApprovalTodoRec[] = [];
let seq = 0;

// ── 种子：原型里出现过的单 + 一批填充数据（让分页、筛选有东西可看）────────────
function seed(now: number) {
  seq = 0;
  const t = (p: Partial<TicketRec> & Pick<TicketRec, "ticketNo" | "title" | "subject" | "priority" | "status">): TicketRec => ({
    typeCode: "ops", assignee: null, pooled: false,
    slaStartMs: now - 2 * HOUR, slaDueAtMs: now + 4 * HOUR,
    pausedAtMs: null, source: { kind: "MANUAL" }, escalated: false, nudgedAtMs: null,
    createdAtMs: now - 2 * HOUR, closedAtMs: null, ...p,
  });
  const sub = (type: string, ref: string, title: string) => ({ type, ref, display: { title } });
  tickets = [
    t({ ticketNo: "WO26092701", title: "机柜离线", subject: sub("cabinet", "CAB1001", "CAB1001 万达广场 B1"), priority: "URGENT",
        status: "PENDING_ACCEPT", assignee: ME, slaStartMs: now - 3.5 * HOUR, slaDueAtMs: now + 28 * MIN,
        source: { kind: "ALARM", ref: "ALM8812" }, createdAtMs: now - 3.5 * HOUR,
        description: "告警引擎：CAB1001 连续 3 次心跳缺失，最后心跳 09:41",
        dedupe: { key: "ALARM:CAB1001:OFFLINE", attachedCount: 2 } }),
    t({ ticketNo: "WO26092688", title: "仓位卡宝", subject: sub("cabinet", "CAB0223", "CAB0223 DXB 机场"), priority: "HIGH",
        status: "PROCESSING", assignee: member("STAFF", "musa", "Musa"), slaStartMs: now - 2.2 * HOUR,
        slaDueAtMs: now + 1.8 * HOUR, source: { kind: "ALARM" } }),
    t({ ticketNo: "WO26092651", title: "更换电池", subject: sub("cabinet", "CAB0510", "等配件"), priority: "NORMAL",
        status: "WAITING_EXTERNAL", assignee: member("STAFF", "musa", "Musa"), slaStartMs: now - 12 * HOUR,
        slaDueAtMs: now + 8 * HOUR, pausedAtMs: now - 6 * HOUR, pausedReason: "等配件", source: { kind: "PATROL" } }),
    t({ ticketNo: "CS26092712", typeCode: "cs-report", title: "无法归还", subject: sub("user", "CU0148", "用户 CU01…48"), priority: "HIGH",
        status: "PROCESSING", assignee: member("CS", "lina", "Lina"), slaStartMs: now - 4 * HOUR,
        slaDueAtMs: now - 42 * MIN, source: { kind: "CUSTOMER" } }),
    t({ ticketNo: "WO26092640", title: "安装新柜", subject: sub("site", "ST0402", "Sharjah City"), priority: "NORMAL",
        status: "PENDING_REVIEW", assignee: member("AGENT", "AG0012", "沙迦服务商"), slaStartMs: now - 30 * HOUR,
        slaDueAtMs: now + 20 * HOUR, source: { kind: "MANUAL" } }),
    t({ ticketNo: "CS26092720", typeCode: "cs-report", title: "客服报障", subject: sub("site", "ST0100", "用户反映无法归还 · DXB 机场"), priority: "HIGH",
        status: "WAITING_EXTERNAL", assignee: ME, slaStartMs: now - 5 * HOUR, slaDueAtMs: now + 3 * HOUR,
        pausedAtMs: now - 40 * MIN, pausedReason: "等用户补充", source: { kind: "CUSTOMER" } }),
    t({ ticketNo: "WO26092733", title: "巡检工单", subject: sub("patrol", "IP-09", "巡检计划 IP-09 派生 · Deira 片区 6 台"), priority: "NORMAL",
        status: "PENDING_ACCEPT", pooled: true, slaStartMs: now - 3 * HOUR, slaDueAtMs: now + 6 * HOUR, source: { kind: "PATROL" } }),
  ];
  // 填充：确定性生成，保证每次刷新一样（测试与截图都依赖这一点）
  const agents = ASSIGNEE_OPTIONS;
  const sites = ["Marina Mall", "Dubai Mall L1", "Yas Mall", "City Centre Deira", "JBR Walk", "Mall of Emirates"];
  const titles = [["ops", "机柜离线"], ["ops", "仓位卡宝"], ["ops", "屏幕黑屏"], ["cs-report", "扣费争议"], ["after-sales", "押金退还"], ["ops", "网络抖动"]] as const;
  const statuses: StatusCode[] = ["PENDING_ACCEPT", "PROCESSING", "PROCESSING", "WAITING_EXTERNAL", "PENDING_REVIEW", "CLOSED", "CLOSED"];
  const prios: PrioCode[] = ["NORMAL", "HIGH", "NORMAL", "LOW", "URGENT"];
  const srcs: SourceCode[] = ["ALARM", "PATROL", "CUSTOMER", "MANUAL"];
  for (let i = 0; i < 24; i++) {
    const [typeCode, title] = titles[i % titles.length];
    const status = statuses[i % statuses.length];
    const due = now + ((i % 9) - 2) * 50 * MIN;
    const created = now - (3 + i) * HOUR;
    tickets.push(t({
      ticketNo: `${typeCode === "ops" ? "WO" : "CS"}260926${String(10 + i).padStart(2, "0")}`,
      typeCode, title, subject: sub("cabinet", `CAB${1100 + i}`, `CAB${1100 + i} ${sites[i % sites.length]}`),
      priority: prios[i % prios.length], status, assignee: agents[i % agents.length],
      slaStartMs: created, slaDueAtMs: due, source: { kind: srcs[i % srcs.length], ref: srcs[i % srcs.length] === "ALARM" ? `ALM${8700 + i}` : undefined },
      pausedAtMs: status === "WAITING_EXTERNAL" ? now - (i + 1) * 20 * MIN : null,
      pausedReason: status === "WAITING_EXTERNAL" ? "等外部" : undefined,
      createdAtMs: created,
      closedAtMs: status === "CLOSED" ? now - (i % 5) * 40 * MIN : null,
    }));
  }
  timelines = {};
  for (const r of tickets) {
    const ev: TimelineEvent[] = [{ seq: 1, atMs: r.createdAtMs, kind: "CREATED", title: "开单",
      actor: r.source.kind === "ALARM" ? "系统（告警引擎）" : r.source.kind === "PATROL" ? "系统（巡检计划）" : "运营", detail: `优先级 ${PRIO[r.priority].name}` }];
    if (r.assignee) ev.push({ seq: 2, atMs: r.createdAtMs + 2 * MIN, kind: "ASSIGNED", title: "派单", actor: "运营 Omar",
      detail: `指派给 ${r.assignee.attrs?.partyType?.name ?? ""} · ${r.assignee.displayName} · SLA 起算` });
    if (r.dedupe) ev.push({ seq: 3, atMs: now - 50 * MIN, kind: "DEDUPE", title: "重复告警并入", actor: "系统",
      detail: `命中去重键 ${r.dedupe.key}，附加到本单` });
    if (r.slaDueAtMs - now > 0 && r.slaDueAtMs - now < DUE_SOON_MS && r.pausedAtMs == null && r.closedAtMs == null) {
      ev.push({ seq: 4, atMs: now - 2 * MIN, kind: "SLA", title: "SLA 提醒 · 即将超时", actor: "系统", detail: "已通知受理方与运营", hot: true });
    }
    timelines[r.ticketNo] = ev;
  }
  approvalTodos = [
    { taskNo: "TK2609270001", approvalNo: "AP2609270001", title: "进场合同 · 运营审批", priority: "HIGH",
      subject: sub("contract", "CT0042", "CT0042 万达广场 · 分成 35% · 12 个月"), startMs: now - 7.4 * HOUR, dueAtMs: now + 4 * HOUR },
    { taskNo: "TK2609270008", approvalNo: "AP2609270008", title: "提现审批", priority: "NORMAL",
      subject: sub("withdrawal", "WD001392", "WD001392 · AG0007 · AED 4,200"), startMs: now - 12 * HOUR, dueAtMs: now + 48 * HOUR },
  ];
}

export function resetFlowDb(now: number = Date.now()) {
  seed(now);
}

// ── 读时计算 ─────────────────────────────────────────────────────────────
function slaStateOf(r: TicketRec, now: number): SlaState {
  if (r.closedAtMs != null) return r.closedAtMs <= r.slaDueAtMs ? "MET" : "BREACHED";
  if (r.pausedAtMs != null) return "PAUSED";
  const left = r.slaDueAtMs - now;
  return left < 0 ? "BREACHED" : left < DUE_SOON_MS ? "WARNING" : "RUNNING";
}

function slaOf(r: TicketRec, now: number): SlaSummary {
  return {
    state: slaStateOf(r, now), startedAtMs: r.slaStartMs, dueAtMs: r.slaDueAtMs,
    pausedMs: r.pausedAtMs != null ? now - r.pausedAtMs : 0,
  };
}

const act = (code: string, name: string, toCategory: StatusCategory): TicketAction =>
  ({ code, name, requires: { fields: [], comment: false }, toCategory });

/** 服务端算动作：状态 × SLA × 受理方。页面只渲染这里给的 */
function actionsOf(r: TicketRec, state: SlaState): TicketAction[] {
  if (!OPEN.includes(r.status)) return [];
  const out: TicketAction[] = [];
  const cat = STATUS[r.status].category;
  const mine = r.assignee?.ref === ME.ref;
  if (r.status === "PENDING_ACCEPT" && mine) out.push(act("ACCEPT", "接单", "IN_PROGRESS"));
  if (state === "BREACHED" && r.assignee && !mine) out.push(act("TAKEOVER", "接管", cat));
  if (r.status === "PENDING_ACCEPT" && !r.pooled && !mine) out.push(act("NUDGE", "催办", cat));
  if ((r.status === "PROCESSING" || (r.status === "PENDING_ACCEPT" && mine)) && !r.escalated) out.push(act("ESCALATE", "升级", cat));
  if (r.status === "PENDING_REVIEW") out.push(act("REVIEW", "复核", "DONE"));
  // 撤单要填原因：列表不出（requires 非空的动作只在详情里办）
  out.push({ code: "CANCEL", name: "撤单", toCategory: "CANCELLED", requires: { fields: [], comment: true } });
  return out;
}

function view(r: TicketRec, now: number): TicketRow & { closedAtMs: number | null } {
  const sla = slaOf(r, now);
  return {
    ticketNo: r.ticketNo, type: { code: r.typeCode, name: TYPE_NAME[r.typeCode] ?? r.typeCode },
    title: r.title, subject: r.subject, priority: PRIO[r.priority],
    status: { code: r.status, name: STATUS[r.status].name }, statusCategory: STATUS[r.status].category,
    assignee: r.assignee ?? undefined, slaState: sla.state,
    slaNextDueAtMs: r.closedAtMs == null ? r.slaDueAtMs : undefined,
    escalated: r.escalated, createdAtMs: r.createdAtMs, updatedAtMs: r.nudgedAtMs ?? r.createdAtMs,
    fields: {
      source: { value: r.source.kind, name: SOURCE_OPT[r.source.kind] },
      ...(r.source.ref ? { sourceRef: { value: r.source.ref } } : {}),
    },
    sla, actions: actionsOf(r, sla.state),
    closedAtMs: r.closedAtMs,
  };
}

const SLA_ORD: Record<SlaState, number> = { BREACHED: 0, WARNING: 1, RUNNING: 2, PAUSED: 3, MET: 4 };

function page<T>(rows: T[], q: { page: number; size: number }): Page<T> {
  const from = (q.page - 1) * q.size;
  return { list: rows.slice(from, from + q.size), total: rows.length };
}

// ── 查询 ─────────────────────────────────────────────────────────────────
type Row = ReturnType<typeof view>;

function matches(t: Row, f: TicketFilter): boolean {
  if (f.typeCode?.length && !f.typeCode.includes(t.type.code)) return false;
  if (f.statusCategory?.length && !f.statusCategory.includes(t.statusCategory)) return false;
  if (f.slaState?.length && !f.slaState.includes(t.slaState)) return false;
  if (f.assigneeAttr?.values.length) {
    const v = t.assignee?.attrs?.[f.assigneeAttr.key]?.value;
    if (!v || !f.assigneeAttr.values.includes(v)) return false;
  }
  if (f.closedFromMs != null && !(t.closedAtMs != null && t.closedAtMs >= f.closedFromMs)) return false;
  if (f.keyword) {
    const k = f.keyword.trim().toLowerCase();
    const hay = [t.ticketNo, t.title, t.subject?.ref ?? "", t.subject?.display.title ?? "",
      String(t.fields.sourceRef?.value ?? ""), t.assignee?.displayName ?? ""].join(" ").toLowerCase();
    if (!hay.includes(k)) return false;
  }
  return true;
}

/** 出参去掉内部辅助字段 */
const out = ({ closedAtMs: _c, ...row }: Row): TicketRow => row;

export function queryTickets(q: TicketQuery, now: number = Date.now()): Page<TicketRow> {
  const rows = tickets.map((r) => view(r, now)).filter((t) => matches(t, q));
  rows.sort((a, b) =>
    SLA_ORD[a.slaState] - SLA_ORD[b.slaState]
    || (a.slaNextDueAtMs ?? Infinity) - (b.slaNextDueAtMs ?? Infinity)
    || a.priority.ordinal - b.priority.ordinal);
  const p = page(rows, q);
  return { list: p.list.map(out), total: p.total };
}

export function getTicket(no: string, now: number = Date.now()): TicketRow {
  const r = tickets.find((x) => x.ticketNo === no);
  if (!r) notFound("工单", "Ticket", no);
  return out(view(r, now));
}

function startOfDay(now: number) {
  const d = new Date(now);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/** 统计卡。每张卡的数字就是用它自己的 queryParams 查出来的 —— 卡与列表同口径是**构造上**保证的 */
export function ticketStats(now: number = Date.now()): TicketStat[] {
  const count = (f: TicketFilter) => tickets.map((r) => view(r, now)).filter((t) => matches(t, f)).length;
  const today = startOfDay(now);
  const cards: Omit<TicketStat, "value">[] = [
    { key: "open", label: "未结", queryParams: { statusCategory: ["NEW", "IN_PROGRESS", "WAITING"] } },
    { key: "inProgress", label: "处理中", queryParams: { statusCategory: ["IN_PROGRESS"] } },
    { key: "dueSoon", label: "即将超时（< 2h）", tone: "warning", queryParams: { slaState: ["WARNING"] } },
    { key: "breached", label: "已超时", tone: "danger", queryParams: { slaState: ["BREACHED"], statusCategory: ["NEW", "IN_PROGRESS", "WAITING"] } },
    { key: "closedToday", label: "今日关闭", queryParams: { statusCategory: ["DONE"], closedFromMs: today } },
  ];
  return cards.map((c) => {
    const value = count(c.queryParams);
    if (c.key !== "closedToday") return { ...c, value };
    const met = count({ ...c.queryParams, slaState: ["MET"] });
    return { ...c, value, sub: value ? `达标率 ${Math.round((met / value) * 100)}%` : "今天还没有关闭的单" };
  });
}

function todosAll(now: number): TodoItem[] {
  const fromApprovals: TodoItem[] = approvalTodos.map((a) => {
    const left = a.dueAtMs - now;
    return {
      taskNo: a.taskNo, kind: "APPROVAL", origin: "PROCESS", claimable: false, title: a.title, subject: a.subject,
      priority: PRIO[a.priority], createdAtMs: a.startMs, dueAtMs: a.dueAtMs,
      sla: { state: left < 0 ? "BREACHED" : left < DUE_SOON_MS ? "WARNING" : "RUNNING", startedAtMs: a.startMs, dueAtMs: a.dueAtMs, pausedMs: 0 },
      approvalNo: a.approvalNo,
    };
  });
  const fromTickets: TodoItem[] = tickets
    .filter((r) => OPEN.includes(r.status) && (r.pooled || r.assignee?.ref === ME.ref))
    .map((r) => {
      const t = view(r, now);
      return {
        taskNo: `TK-${r.ticketNo}`, kind: "WORK", origin: "TICKET", claimable: r.pooled,
        title: `${r.title} · ${t.status.name}`, subject: r.subject, priority: t.priority,
        createdAtMs: r.createdAtMs, dueAtMs: r.slaDueAtMs, sla: t.sla,
        ticketNo: r.ticketNo, ticketType: t.type,
      } satisfies TodoItem;
    });
  return [...fromApprovals, ...fromTickets].sort(
    (a, b) => SLA_ORD[a.sla?.state ?? "RUNNING"] - SLA_ORD[b.sla?.state ?? "RUNNING"] || (a.dueAtMs ?? Infinity) - (b.dueAtMs ?? Infinity));
}

export function queryTodos(q: TodoQuery, now: number = Date.now()): Page<TodoItem> {
  const rows = todosAll(now).filter((t) =>
    (q.origin === "ALL" || t.origin === q.origin) && (q.claimable === undefined || t.claimable === q.claimable));
  return page(rows, q);
}

export function countTodos(now: number = Date.now()): TodoCount {
  const all = todosAll(now);
  return {
    all: all.length,
    process: all.filter((t) => t.origin === "PROCESS").length,
    ticket: all.filter((t) => t.origin === "TICKET" && !t.claimable).length,
    claimable: all.filter((t) => t.claimable).length,
  };
}

// ── 写操作（状态机在这里）──────────────────────────────────────────────────
function row(no: string): TicketRec {
  const r = tickets.find((x) => x.ticketNo === no);
  if (!r) notFound("工单", "Ticket", no);
  return r;
}

/** 抢单：只有还在池里、未被领走的单能抢；抢到即派给我并进入处理中 */
export function claimTicket(no: string, now: number = Date.now()): TicketRow {
  const r = row(no);
  if (!r.pooled || r.assignee) {
    fail(`${no} 已被他人领取或不在抢单池里，刷新后再看`, `${no} was already claimed or is not in the pool — refresh and try again`);
  }
  r.pooled = false;
  r.assignee = ME;
  r.status = "PROCESSING";
  log(r.ticketNo, now, "ASSIGNED", "从抢单池领取", ME.displayName);
  seq++;
  return out(view(r, now));
}

/** 迁移：只接受服务端当前给出的动作；其余一律拒绝（不信页面按钮） */
export function transitionTicket(no: string, code: string, now: number = Date.now(), comment?: string): TicketRow {
  const r = row(no);
  const allowed = actionsOf(r, slaStateOf(r, now));
  const act = allowed.find((a) => a.code === code);
  if (!act) {
    fail(`工单 ${no} 当前是「${STATUS[r.status].name}」，不能执行 ${code}`, `Ticket ${no} is "${r.status}"; action ${code} is not allowed now`);
  }
  if (act.requires.comment && !comment?.trim()) {
    fail(`「${act.name}」必须填写原因（会记入时间线）`, `"${act.name}" requires a reason (it goes into the timeline)`);
  }
  const from = STATUS[r.status].name;
  switch (code) {
    case "NUDGE": r.nudgedAtMs = now; break;
    case "ESCALATE": r.escalated = true; break;
    case "TAKEOVER": r.assignee = ME; break;
    case "ACCEPT": r.status = "PROCESSING"; break;
    case "REVIEW": r.status = "CLOSED"; r.closedAtMs = now; r.pausedAtMs = null; break;
    case "CANCEL": r.status = "CANCELLED"; r.closedAtMs = now; r.pausedAtMs = null; r.cancelReason = comment; break;
  }
  const to = STATUS[r.status].name;
  log(r.ticketNo, now, code === "ESCALATE" ? "ESCALATED" : "TRANSITION",
    from === to ? act.name : `${act.name}：${from} → ${to}`, ME.displayName, comment?.trim() || undefined);
  return out(view(r, now));
}

/** 批量改派：原因必填；已结的单不能改派（整批拒绝，不做部分成功，免得界面上说不清哪些改了） */
export function reassignTickets(nos: string[], to: Member, reason: string, now: number = Date.now()): TicketRow[] {
  if (!nos.length) fail("没有选中任何工单", "No tickets selected");
  if (!reason.trim()) fail("改派必须填写原因（会记入工单时间线）", "A reason is required to reassign (it goes into the ticket timeline)");
  const rows = nos.map(row);
  const closed = rows.filter((r) => !OPEN.includes(r.status)).map((r) => r.ticketNo);
  if (closed.length) {
    fail(`以下工单已结，不能改派：${closed.join("、")}`, `These tickets are already closed and cannot be reassigned: ${closed.join(", ")}`);
  }
  for (const r of rows) {
    r.assignee = to; r.pooled = false;
    log(r.ticketNo, now, "ASSIGNED", "改派", ME.displayName, `改派给 ${to.attrs?.partyType?.name ?? ""} · ${to.displayName}：${reason.trim()}`);
  }
  return rows.map((r) => out(view(r, now)));
}

/** 改派时可选的受理方（接后端后：成员 / 组接口） */
export const ASSIGNEE_OPTIONS: Member[] = [
  member("AGENT", "AG0007", "迪拜城运"), member("AGENT", "AG0012", "沙迦服务商"),
  member("STAFF", "musa", "Musa"), member("STAFF", "omar", "Omar"), member("CS", "lina", "Lina"),
];

/** 受理方筛选的选项（成员属性 partyType 的取值；接后端后由成员属性定义给出） */
export const PARTY_OPTIONS: Named[] = Object.entries(PARTY).map(([code, name]) => ({ code, name }));

// ── 工单详情 · 时间线 · 备注 · 退回抢单池 ────────────────────────────────────
function log(no: string, atMs: number, kind: TimelineEvent["kind"], title: string, actor: string, detail?: string) {
  const list = (timelines[no] ??= []);
  list.push({ seq: list.length + 1, atMs, kind, title, actor, detail });
}

/** GET /workspaces/{ws}/tickets/{no}：行 + 全部字段 + 参与者 + 每个 SLA 指标 + 动作 */
export function getTicketView(no: string, now: number = Date.now()): TicketView {
  const r = row(no);
  const base = out(view(r, now));
  // 待接单时，最近的运行中指标是「响应」（它就是列表上那个 SLA）；「解决」截止更晚。
  // 接单后响应达标，列表上的 SLA 换成「解决」。与原型 P5 同口径（响应 剩 28 分钟 · 解决 今天 17:32 前）。
  const accepted = r.status !== "PENDING_ACCEPT";
  const main = slaOf(r, now);
  const resolveDue = accepted ? r.slaDueAtMs : r.slaDueAtMs + 6 * HOUR;
  const resolveState: SlaState = main.state === "PAUSED" || main.state === "MET" ? main.state
    : resolveDue < now ? "BREACHED" : resolveDue - now < DUE_SOON_MS ? "WARNING" : "RUNNING";
  const metrics: SlaMetric[] = [
    accepted
      ? { code: "respond", name: "响应", state: "MET", startedAtMs: r.slaStartMs, dueAtMs: r.slaStartMs + 30 * MIN, pausedMs: 0 }
      : { code: "respond", name: "响应", ...main },
    { code: "resolve", name: "解决", state: resolveState, startedAtMs: r.slaStartMs, dueAtMs: resolveDue, pausedMs: main.pausedMs },
  ];
  return {
    ...base,
    description: r.description,
    fieldList: [
      { code: "subject", label: "对象", value: { value: r.subject.ref, name: r.subject.display.title } },
      { code: "source", label: "来源", value: { value: r.source.kind, name: SOURCE_OPT[r.source.kind] } },
      ...(r.source.ref ? [{ code: "sourceRef", label: "来源单号", value: { value: r.source.ref } }] : []),
      { code: "type", label: "类型", value: { value: r.typeCode, name: TYPE_NAME[r.typeCode] } },
    ],
    participants: [
      ...(r.assignee ? [{ role: { code: "ASSIGNEE", name: "受理方" }, member: r.assignee }] : []),
      { role: { code: "OWNER", name: "负责运营" }, member: { ref: "omar", displayName: "Omar" } },
    ],
    slaMetrics: metrics,
    ...(r.status === "CANCELLED" ? { resolution: { code: "CANCELLED", name: `已撤单：${r.cancelReason ?? ""}` } } : {}),
    ...(r.dedupe ? { dedupe: r.dedupe } : {}),
  };
}

/** GET /workspaces/{ws}/tickets/{no}/timeline：新的在上 */
export function getTimeline(no: string): TimelineEvent[] {
  row(no);
  return [...(timelines[no] ?? [])].sort((a, b) => b.atMs - a.atMs || b.seq - a.seq);
}

/** POST /workspaces/{ws}/tickets/{no}/comments */
export function addComment(no: string, body: string, now: number = Date.now()): TimelineEvent[] {
  const r = row(no);
  if (!body.trim()) fail("备注不能为空", "The note cannot be empty");
  if (body.length > 1000) fail("备注不能超过 1000 字", "The note cannot exceed 1000 characters");
  log(r.ticketNo, now, "COMMENT", "备注", ME.displayName, body.trim());
  return getTimeline(no);
}

/** POST /workspaces/{ws}/tickets/{no}/assign：to 为 null = 退回抢单池。原因必填 */
export function assignTicket(no: string, to: Member | null, reason: string, now: number = Date.now()): TicketRow {
  const r = row(no);
  if (!OPEN.includes(r.status)) fail(`工单 ${no} 已结，不能改派`, `Ticket ${no} is closed and cannot be reassigned`);
  if (!reason.trim()) fail("改派必须填写原因（会记入工单时间线）", "A reason is required to reassign (it goes into the ticket timeline)");
  if (to) {
    r.assignee = to; r.pooled = false;
    log(no, now, "ASSIGNED", "改派", ME.displayName, `改派给 ${to.attrs?.partyType?.name ?? ""} · ${to.displayName}：${reason.trim()}`);
  } else {
    r.assignee = null; r.pooled = true; r.status = "PENDING_ACCEPT";
    log(no, now, "ASSIGNED", "退回抢单池", ME.displayName, reason.trim());
  }
  return out(view(r, now));
}

// 放在文件末尾：种子要用到上面定义的 ASSIGNEE_OPTIONS 与 log（const 有暂时性死区）
resetFlowDb();
