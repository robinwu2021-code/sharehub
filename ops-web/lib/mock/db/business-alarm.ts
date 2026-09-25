import type {
  AlarmRecord, AlarmSummary, AlarmDomainCount, DispositionPreview, AlarmRoute, AlarmRouteReq,
  AlarmCodeStat, AlarmTodo, AlarmDisposition, AlarmDetail, AlarmDisposeResult, AlarmTodoQ,
  AlarmDomain, WorkOrder, WorkOrderPriority, WorkOrderType,
} from "../../types";
import { fail } from "../../biz-error";
import {
  alarmRecords as alarms, alarmCodes, alarmTodos as todos, alarmTimeline, logAlarm, businessOf,
  __resetTimelines,
} from "./alarm";
import { workOrders } from "./workorder";
import { paginate, nextNo } from "./helpers";

/**
 * 业务告警与待办的 mock（镜像后端 AlarmEngine 的 preview / disposeNow 与 AlarmTodoService）。
 *
 * <h3>告警不是终点，处置才是</h3>
 * 一条告警最终要落到五者之一：自愈 / 开工单 / 转客服 / 挂待办 / 仅通知。
 * 没有处置的告警只会堆着，堆到没人看 —— 而那时真正要紧的那条也一起被埋了。
 * 所以 {@link disposeAlarm} 会**真的产出东西**（工单行 / 待办行），不是把状态一改了事。
 *
 * <h3>预览与执行走同一条路由</h3>
 * {@link alarmDispositionPreview} 与 {@link disposeAlarm} 调同一个 {@link planOf}。
 * 各算一遍的话，预览说「并进 WO-1」而执行新开了一张 —— 而这恰恰是预览存在的意义。
 */

/** 各码自己配过的路由；没配过的码走 {@link DEFAULT_ROUTES}。 */
const routes = new Map<string, AlarmRoute[]>();
let tSeq = 8100;
let csSeq = 3100;

const now = () => new Date().toISOString();
const OPEN_STATES: AlarmRecord["status"][] = ["OPEN", "ACKED"];
const PRIORITIES: WorkOrderPriority[] = ["LOW", "MEDIUM", "HIGH", "URGENT"];

/** 默认路由：没配过的码走这里。`*` = 兜底（与后端存库值一致）。 */
const DEFAULT_ROUTES: AlarmRoute[] = [
  { alarmCode: "*", cause: "OFFLINE", disposition: "WORK_ORDER", woType: "FAULT", priorityDelta: 1, fallback: "NOTIFY" },
  { alarmCode: "*", cause: "NO_STOCK", disposition: "WORK_ORDER", woType: "REFILL", priorityDelta: 0, fallback: "NOTIFY" },
  { alarmCode: "*", cause: "FULL", disposition: "WORK_ORDER", woType: "REFILL", priorityDelta: 0, fallback: "NOTIFY" },
  { alarmCode: "*", cause: "*", disposition: "NOTIFY", woType: null, priorityDelta: 0, fallback: null },
];

function findAlarm(alarmNo: string): AlarmRecord {
  const a = alarms.find((x) => x.alarmNo === alarmNo);
  if (!a) fail(`告警不存在：${alarmNo}`, `Alarm not found: ${alarmNo}`, `التنبيه غير موجود: ${alarmNo}`);
  return a;
}

/**
 * 选路由：该码精确成因 → 该码兜底（`*`）→ 没配过路由的码才用全局默认。
 * 业务码配过路由却没命中（成因不在表里）时返回 null，交给码的首选处置 —— 与后端 routeOf 一致。
 */
function routeOf(a: AlarmRecord): AlarmRoute | null {
  const cause = a.business?.cause ?? null;
  const own = routes.get(a.alarmCode);
  if (own) return own.find((r) => r.cause === cause) ?? own.find((r) => r.cause === "*" || !r.cause) ?? null;
  if (businessOf(a.alarmCode)) return null;
  return DEFAULT_ROUTES.find((r) => r.cause === cause) ?? DEFAULT_ROUTES[DEFAULT_ROUTES.length - 1];
}

const bump = (p: WorkOrderPriority, delta: number) =>
  PRIORITIES[Math.max(0, Math.min(PRIORITIES.length - 1, PRIORITIES.indexOf(p) + delta))];

/** 处置方案：预览与执行的唯一来源。 */
function planOf(a: AlarmRecord): DispositionPreview {
  const code = businessOf(a.alarmCode);
  const route = routeOf(a);
  // 存量设备告警（码无业务配置且没配路由）按故障工单处置 —— 后端 preview 对 code==null 同样如此
  const type: AlarmDisposition = route?.disposition ?? code?.disposition ?? "WORK_ORDER";
  const woType = route?.woType ?? "FAULT";
  const base: WorkOrderPriority = a.business?.priority ?? code?.basePriority ?? "MEDIUM";
  const priority = bump(base, route?.priorityDelta ?? 0);
  /*
   * 并单：同一主体（缺主体时同站点）上已有未关闭的开单类告警，就并进它的工单。
   * 不并的话，一台离线的柜子每轮评估都开一张单，维修工到现场发现五张一样的。
   *
   * ⚠️ **只有开单类处置才谈得上并单** —— 处置方式是「仅通知」时显示一个并单目标，
   * 预览就在说谎（6a 的用例抓到过）。
   */
  let mergeIntoWoNo: string | null = null;
  if (type === "WORK_ORDER") {
    const key = (x: AlarmRecord) => x.business?.subjectNo ?? x.cabinetNo ?? x.siteNo;
    const scope = code?.mergeScope ?? "DEVICE";
    const peer = alarms.find((x) => OPEN_STATES.includes(x.status)
      && x.business?.dispositionType === "WORK_ORDER" && x.business.dispositionRef
      && (scope === "DEVICE" ? key(x) === key(a) : x.siteNo === a.siteNo));
    mergeIntoWoNo = peer?.business?.dispositionRef
      ?? (a.business?.dispositionType === "WORK_ORDER" ? a.business.dispositionRef : null)
      ?? (!a.business && a.workOrderNo && a.status !== "CLOSED" ? a.workOrderNo : null);
  }
  return {
    type,
    woType: type === "WORK_ORDER" ? woType : null,
    priority,
    // mock 没有「站点运维责任人」的候选人服务：不编一个人出来，宁可显示「按站点派单规则」
    assigneeType: null,
    assigneeNo: null,
    mergeIntoWoNo,
    todoRole: type === "TODO" ? code?.ownerRole ?? "OPS" : null,
    fallback: route?.fallback ?? null,
  };
}

export const alarmDispositionPreview = (alarmNo: string): DispositionPreview => planOf(findAlarm(alarmNo));

function markDisposed(a: AlarmRecord, type: AlarmDisposition, ref: string | null, note: string) {
  if (a.business) {
    a.business.dispositionType = type;
    a.business.dispositionRef = ref;
  }
  // 工单号同时落老列 workOrderNo：存量设备告警只有这一列（后端 wo_no 同理），列表的「关联工单」也读它
  if (type === "WORK_ORDER") a.workOrderNo = ref;
  logAlarm(a.alarmNo, "DISPOSE", note, "admin");
}

/**
 * 立即处置。返回形状与后端 `POST /records/{no}/dispose` 一致：`{ alarmNo, dispositionRef }`，
 * 自愈成功或仅通知时 `dispositionRef` 为 null。
 *
 * - 已关闭 → 拒（后端 error.alarm.closed）；
 * - **已处置过 → 返回首次的单号**（幂等），不产出第二张单；
 * - 开工单：真的往 workOrders 里插一行（mock 必须真改 db），或并进已有的单。
 */
export function disposeAlarm(alarmNo: string): AlarmDisposeResult {
  const a = findAlarm(alarmNo);
  if (!OPEN_STATES.includes(a.status)) {
    fail("已关闭的告警不需要处置", "Closed alarm needs no disposition", "التنبيه مغلق");
  }
  const prior = a.business ? a.business.dispositionRef : a.workOrderNo;
  if (prior) return { alarmNo, dispositionRef: prior };
  alarmTimeline(a); // 先落种子时间线
  const plan = planOf(a);
  switch (plan.type) {
    case "WORK_ORDER": {
      let woNo = plan.mergeIntoWoNo;
      if (!woNo) {
        const cab = a.cabinetNo;
        const wo: WorkOrder = {
          woNo: nextNo("WO", workOrders, 70000, "woNo"), type: (plan.woType ?? "FAULT") as WorkOrderType,
          source: "ALERT", sourceNo: a.alarmNo, priority: plan.priority ?? "MEDIUM", cabinetNo: cab,
          locationName: a.siteName, status: "CREATED", assigneeName: null, slaDueAt: null,
          description: `${codeName(a.alarmCode)}｜告警 ${a.alarmNo}`, createdAt: now(), rejectCount: 0,
        };
        workOrders.unshift(wo);
        woNo = wo.woNo;
      }
      markDisposed(a, "WORK_ORDER", woNo, plan.mergeIntoWoNo ? `并入工单 ${woNo}` : `工单 ${woNo}`);
      return { alarmNo, dispositionRef: woNo };
    }
    case "CS_CASE": {
      const ticketNo = `CS${csSeq++}`;
      markDisposed(a, "CS_CASE", ticketNo, `客服单 ${ticketNo}`);
      return { alarmNo, dispositionRef: ticketNo };
    }
    case "TODO": {
      const t: AlarmTodo = {
        todoNo: `ATD${tSeq++}`, alarmNo, alarmCode: a.alarmCode, roleCode: plan.todoRole,
        assigneeNo: null, title: `${codeName(a.alarmCode)}：${a.business?.subjectNo ?? a.siteNo ?? a.cabinetNo ?? ""}`.trim(),
        status: "OPEN", siteNo: a.siteNo ?? null, createdAt: now(),
        doneAt: null, doneBy: null, doneNote: null,
      };
      todos.unshift(t);
      markDisposed(a, "TODO", t.todoNo, `待办 ${t.todoNo}`);
      return { alarmNo, dispositionRef: t.todoNo };
    }
    case "AUTO_FIX": {
      // mock 的自愈一律成功：撤单 / 结单都是系统动作，成功即以 AUTO_FIXED 关闭
      markDisposed(a, "AUTO_FIX", null, "系统自愈");
      a.status = "CLOSED";
      a.closeReason = "AUTO_FIXED";
      a.closeNote = "系统自愈成功";
      a.closedBy = "SYSTEM";
      a.closedAt = now();
      logAlarm(a.alarmNo, "CLOSE", "AUTO_FIXED 系统自愈成功", "SYSTEM");
      return { alarmNo, dispositionRef: null };
    }
    default: {
      // NOTIFY：只告知、不产生任何人的工作 —— 记下处置方式，免得下一轮又当成「未处置」
      markDisposed(a, "NOTIFY", null, "仅通知");
      return { alarmNo, dispositionRef: null };
    }
  }
}

const codeName = (code: string) => alarmCodes.find((c) => c.code === code)?.message ?? code;

/** 详情：记录 + 码名称与预案 + 证据 + 时间线 + 同主体近 7 天。 */
export function getAlarmDetail(alarmNo: string): AlarmDetail {
  const a = findAlarm(alarmNo);
  const code = alarmCodes.find((c) => c.code === a.alarmCode);
  const subject = a.business?.subjectNo ?? a.cabinetNo;
  const weekAgo = Date.now() - 7 * 86400_000;
  // 证据：站点级告警挂站内柜子的离线信号；其余挂主体本身。真后端是判定器落库的 JSON，这里照形状造
  const evidence = a.business
    ? JSON.stringify(a.business.subjectType === "SITE"
      ? [{ signal: a.business.cause ?? "OFFLINE", cabinetNo: a.cabinetNo ?? "CAB-", slot: null, at: a.occurredAt, note: "DEPLOYED" }]
      : [{ signal: a.business.cause ?? "-", cabinetNo: a.cabinetNo, slot: null, at: a.occurredAt, note: null }])
    : null;
  return {
    record: a,
    codeName: code?.message ?? null,
    suggestion: code?.suggestion ?? null,
    evidence,
    timeline: [...alarmTimeline(a)],
    recentSameSubject: subject
      ? alarms.filter((x) => x.alarmNo !== a.alarmNo
        && ((x.business?.subjectNo ?? x.cabinetNo) === subject || x.cabinetNo === subject)
        && new Date(x.occurredAt).getTime() >= weekAgo)
      : [],
  };
}

// ——— 路由配置 ———

export const listAlarmRoutes = (code: string): AlarmRoute[] =>
  routes.get(code) ?? DEFAULT_ROUTES.map((r) => ({ ...r, alarmCode: code }));

/** 整组覆盖。校验与后端 saveRoutes 一致：码必须存在、成因不重复、处置/兜底取值合法、增量夹在 ±2。 */
export function saveAlarmRoutes(code: string, reqs: AlarmRouteReq[]): AlarmRoute[] {
  if (!alarmCodes.some((c) => c.code === code)) fail(`告警码不存在：${code}`, `Alarm code not found: ${code}`, `رمز التنبيه غير موجود: ${code}`);
  if (!reqs?.length) fail("至少保留一条路由——没有路由的码，告警产生了也不知道该干嘛", "At least one route", "مطلوب مسار واحد على الأقل");
  const seen = new Set<string>();
  for (const r of reqs) {
    if (!r.disposition) fail("每条路由都要指定处置方式", "Disposition required", "الإجراء مطلوب");
    const key = r.cause || "*";
    // 同一成因配两条，命中哪条取决于顺序——那是最难查的一类配置错误
    if (seen.has(key)) fail(`成因「${key}」配了两条路由`, `Duplicate cause ${key}`, `سبب مكرر ${key}`);
    seen.add(key);
  }
  const saved: AlarmRoute[] = reqs.map((r) => ({
    alarmCode: code, cause: r.cause || "*", disposition: r.disposition,
    woType: r.disposition === "WORK_ORDER" ? r.woType || null : null,
    priorityDelta: Math.max(-2, Math.min(2, r.priorityDelta ?? 0)), fallback: r.fallback || null,
  }));
  routes.set(code, saved);
  return saved;
}

/**
 * 每码统计。三个比率是**调规则的依据** ——
 * 误报率高 = 规则太敏感，自愈率高 = 不该开单，撤单率高 = 开单延迟太短、白跑了人。
 * 自愈率与后端同口径：SELF_HEALED + AUTO_FIXED。撤单率 mock 以 SUPERSEDED 近似（mock 工单没有撤单原因）。
 */
export function alarmCodeStats(_days = 30): AlarmCodeStat[] {
  const byCode = new Map<string, AlarmRecord[]>();
  for (const a of alarms) byCode.set(a.alarmCode, [...(byCode.get(a.alarmCode) ?? []), a]);
  return [...byCode.entries()].map(([code, list]) => {
    const rate = (n: number) => (list.length ? Math.round((n / list.length) * 1000) / 1000 : 0);
    return {
      code,
      total: list.length,
      falseAlarmRate: rate(list.filter((a) => a.closeReason === "FALSE_ALARM").length),
      selfHealRate: rate(list.filter((a) => a.closeReason === "SELF_HEALED" || a.closeReason === "AUTO_FIXED").length),
      withdrawnRate: rate(list.filter((a) => a.closeReason === "SUPERSEDED").length),
    };
  });
}

// ——— 待办 ———

/**
 * 待办列表。`mine`（缺省 true）在 mock 里等价于「未完成的都算我的」：
 * 没有真实岗位上下文，造一个假的归属反而会让人以为筛选生效了。
 */
export const listAlarmTodos = (q: AlarmTodoQ = {}) =>
  paginate(todos, q.page, q.size, (t) =>
    (q.status ? t.status === q.status : q.mine === false ? true : t.status === "OPEN"));

export const alarmTodoCount = (): { open: number } => ({
  open: todos.filter((t) => t.status === "OPEN").length,
});

/**
 * 办结待办。与后端 onDispositionDone 同口径：办结后关联告警**关闭（RESOLVED）** ——
 * mock 没有「条件是否仍成立」的判定器，按已不成立处理；真后端仍成立时会清空处置、下一轮重开待办。
 */
export function doneAlarmTodo(todoNo: string, note?: string): AlarmTodo {
  const t = todos.find((x) => x.todoNo === todoNo);
  if (!t) fail(`待办不存在：${todoNo}`, `Todo not found: ${todoNo}`, `المهمة غير موجودة: ${todoNo}`);
  if (t.status !== "OPEN") fail(`待办已是「${t.status}」，不能再办结`, `Todo already ${t.status}`, `المهمة ${t.status}`);
  t.status = "DONE";
  t.doneAt = now();
  t.doneBy = "admin";
  t.doneNote = note?.trim() || null;
  const a = alarms.find((x) => x.alarmNo === t.alarmNo);
  if (a && OPEN_STATES.includes(a.status) && a.business?.dispositionRef === t.todoNo) {
    alarmTimeline(a);
    a.status = "CLOSED";
    a.closeReason = "RESOLVED";
    a.closeNote = `TODO ${t.todoNo} 已完成`;
    a.closedBy = "SYSTEM";
    a.closedAt = now();
    logAlarm(a.alarmNo, "CLOSE", `RESOLVED TODO ${t.todoNo} 已完成`, "SYSTEM");
  }
  return t;
}

// ——— 摘要 ———

/** 后端口径：无域的存量设备告警计入 AVAILABILITY；被取代的子告警不计（只数顶层）。 */
const domainOf = (a: AlarmRecord): AlarmDomain => a.business?.domain ?? "AVAILABILITY";

export function alarmSummary(): AlarmSummary {
  const open = alarms.filter((a) => OPEN_STATES.includes(a.status));
  const byDomain: Partial<Record<AlarmDomain, AlarmDomainCount>> = {};
  for (const a of open) {
    if (a.business?.parentAlarmNo) continue;
    const d = domainOf(a);
    const cur = byDomain[d] ?? { open: 0, critical: 0 };
    byDomain[d] = { open: cur.open + 1, critical: cur.critical + (a.level === "CRITICAL" ? 1 : 0) };
  }
  const today = now().slice(0, 10);
  return {
    byDomain,
    // 已处置但还没关闭：开了工单不等于问题好了，这一格最容易被忽略
    disposedOpen: open.filter((a) => a.business?.dispositionRef).length,
    autoRecoveredToday: alarms.filter((a) => a.status === "CLOSED"
      && (a.closeReason === "SELF_HEALED" || a.closeReason === "AUTO_FIXED")
      && String(a.closedAt ?? "").startsWith(today)).length,
  };
}

/** 仅供测试重置：路由表清空，待办与时间线回到种子。 */
export function __resetTodos(): void {
  routes.clear();
  __resetTimelines();
}
