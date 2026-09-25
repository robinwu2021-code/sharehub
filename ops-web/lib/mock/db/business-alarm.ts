import type {
  AlarmRecord, AlarmSummary, AlarmDomainCount, DispositionPreview, AlarmRoute, AlarmRouteReq,
  AlarmCodeStat, AlarmTodo, AlarmDisposition, PageResult,
} from "../../types";
import { fail } from "../../biz-error";
import { alarmRecords as alarms } from "./alarm";
import { paginate } from "./helpers";

/**
 * 业务告警与待办的 mock。
 *
 * <h3>告警不是终点，处置才是</h3>
 * 一条告警最终要落到四者之一：自愈 / 开工单 / 转客服 / 仅通知。
 * 没有处置的告警只会堆着，堆到没人看 —— 而那时真正要紧的那条也一起被埋了。
 * 所以 {@link disposeAlarm} 会**真的产出东西**（待办 / 工单号），不是把状态一改了事。
 *
 * <h3>预览与执行走同一条路由</h3>
 * {@link alarmDispositionPreview} 与 {@link disposeAlarm} 调同一个 {@link routeOf}。
 * 各算一遍的话，预览说「派给张三」而执行派给了李四 —— 而这恰恰是预览存在的意义。
 */

const todos: AlarmTodo[] = [];
const routes = new Map<string, AlarmRoute[]>();
let tSeq = 8000;
let woSeq = 9500;

const now = () => new Date().toISOString();

/** 默认路由：没配过的码走这里。不给默认的话，新码的告警会卡在「不知道该干嘛」。 */
const DEFAULT_ROUTES: AlarmRoute[] = [
  { alarmCode: "*", cause: "OFFLINE", disposition: "WORK_ORDER", woType: "FAULT", priorityDelta: 1, fallback: "NOTIFY" },
  { alarmCode: "*", cause: "NO_STOCK", disposition: "WORK_ORDER", woType: "REFILL", priorityDelta: 0, fallback: "NOTIFY" },
  { alarmCode: "*", cause: "FULL", disposition: "WORK_ORDER", woType: "REFILL", priorityDelta: 0, fallback: "NOTIFY" },
  { alarmCode: "*", cause: "NO_CONTRACT", disposition: "CS_CASE", woType: null, priorityDelta: 0, fallback: "NOTIFY" },
  { alarmCode: "*", cause: null, disposition: "NOTIFY", woType: null, priorityDelta: 0, fallback: null },
];

function findAlarm(alarmNo: string): AlarmRecord {
  const a = alarms.find((x) => x.alarmNo === alarmNo);
  if (!a) fail(`告警不存在：${alarmNo}`, `Alarm not found: ${alarmNo}`, `التنبيه غير موجود: ${alarmNo}`);
  return a;
}

/**
 * 选路由。**预览与执行共用** —— 见文件头。
 *
 * <p>匹配顺序：该码的精确成因 → 该码的兜底 → 全局成因 → 全局兜底。
 * 从具体到宽泛，先命中先用。
 */
function routeOf(a: AlarmRecord): AlarmRoute {
  const cause = (a as unknown as { cause?: string }).cause ?? null;
  const own = routes.get(a.alarmCode) ?? [];
  return own.find((r) => r.cause === cause)
    ?? own.find((r) => !r.cause)
    ?? DEFAULT_ROUTES.find((r) => r.cause === cause)
    ?? DEFAULT_ROUTES[DEFAULT_ROUTES.length - 1];
}

export function alarmDispositionPreview(alarmNo: string): DispositionPreview {
  const a = findAlarm(alarmNo);
  const r = routeOf(a);
  /*
   * 并单判定：同一个柜子上已有未关闭的同类工单时，并进去而不是再开一张。
   * 不并的话，一台离线的柜子每次规则评估都开一张单，维修工到现场发现五张一样的。
   *
   * ⚠️ **只有开单类处置才谈得上并单**。第一版没加这个条件，于是
   * 处置方式是「仅通知」时预览照样显示一个并单目标 —— 预览在说谎，
   * 而预览存在的意义就是不说谎（用例 business-alarm.test.ts 抓到的）。
   */
  const mergeIntoWoNo = r.disposition === "WORK_ORDER" && a.workOrderNo && a.status !== "CLOSED"
    ? a.workOrderNo
    : null;
  return {
    type: r.disposition,
    woType: r.woType,
    priority: r.priorityDelta && r.priorityDelta > 0 ? "HIGH" : "MEDIUM",
    assigneeType: r.disposition === "WORK_ORDER" ? "ROLE" : null,
    assigneeNo: null,
    mergeIntoWoNo,
    todoRole: r.disposition === "CS_CASE" ? "CS" : "OPS",
    fallback: r.fallback,
  };
}

/** 执行处置。产出真东西：并单号 / 新工单号 / 待办号。 */
export function disposeAlarm(alarmNo: string): Record<string, unknown> {
  const a = findAlarm(alarmNo);
  if (a.status === "CLOSED") {
    fail("已关闭的告警不需要处置", "Closed alarm needs no disposition", "التنبيه مغلق");
  }
  const p = alarmDispositionPreview(alarmNo);
  const out: Record<string, unknown> = { alarmNo, disposition: p.type };

  if (p.type === "WORK_ORDER") {
    if (p.mergeIntoWoNo) {
      out.mergedInto = p.mergeIntoWoNo;
    } else {
      const woNo = `WO${woSeq++}`;
      a.workOrderNo = woNo;
      out.workOrderNo = woNo;
    }
  }
  if (p.type === "CS_CASE" || p.type === "WORK_ORDER" || p.type === "NOTIFY") {
    const t: AlarmTodo = {
      todoNo: `TD${tSeq++}`, alarmNo, alarmCode: a.alarmCode, roleCode: p.todoRole,
      assigneeNo: null, title: `${a.alarmCode} · ${a.cabinetNo ?? ""}`.trim(),
      status: "OPEN", siteNo: a.siteNo ?? null, createdAt: now(),
      doneAt: null, doneBy: null, doneNote: null,
    };
    todos.unshift(t);
    out.todoNo = t.todoNo;
  }
  if (p.type === "AUTO_FIX") {
    a.status = "CLOSED";
    out.autoFixed = true;
  }
  // 处置过的告警进入「已处置未关闭」—— 开了工单不等于问题好了
  (a as unknown as { disposedAt?: string }).disposedAt = now();
  return out;
}

// ——— 路由配置 ———

export const listAlarmRoutes = (code: string): AlarmRoute[] =>
  routes.get(code) ?? DEFAULT_ROUTES.map((r) => ({ ...r, alarmCode: code }));

export function saveAlarmRoutes(code: string, reqs: AlarmRouteReq[]): AlarmRoute[] {
  if (!reqs?.length) fail("至少保留一条路由——没有路由的码，告警产生了也不知道该干嘛", "At least one route", "مطلوب مسار واحد على الأقل");
  const seen = new Set<string>();
  for (const r of reqs) {
    if (!r.disposition) fail("每条路由都要指定处置方式", "Disposition required", "الإجراء مطلوب");
    const key = r.cause ?? "*";
    // 同一成因配两条，命中哪条取决于顺序——那是最难查的一类配置错误
    if (seen.has(key)) fail(`成因「${key}」配了两条路由`, `Duplicate cause ${key}`, `سبب مكرر ${key}`);
    seen.add(key);
  }
  const saved: AlarmRoute[] = reqs.map((r) => ({
    alarmCode: code, cause: r.cause ?? null, disposition: r.disposition,
    woType: r.woType ?? null, priorityDelta: r.priorityDelta ?? 0, fallback: r.fallback ?? null,
  }));
  routes.set(code, saved);
  return saved;
}

/**
 * 每码统计。三个比率是**调规则的依据** ——
 * 误报率高 = 规则太敏感，自愈率高 = 不该开单，撤单率高 = 处置路由配错了。
 */
export function alarmCodeStats(): AlarmCodeStat[] {
  const byCode = new Map<string, AlarmRecord[]>();
  for (const a of alarms) {
    byCode.set(a.alarmCode, [...(byCode.get(a.alarmCode) ?? []), a]);
  }
  return [...byCode.entries()].map(([code, list]) => {
    const closed = list.filter((a) => a.status === "CLOSED");
    const reasonOf = (a: AlarmRecord) => (a as unknown as { closeReason?: string }).closeReason;
    const rate = (n: number) => (list.length ? Number((n / list.length).toFixed(3)) : 0);
    return {
      code,
      total: list.length,
      falseAlarmRate: rate(closed.filter((a) => reasonOf(a) === "FALSE_ALARM").length),
      selfHealRate: rate(closed.filter((a) => reasonOf(a) === "SELF_HEALED").length),
      withdrawnRate: rate(closed.filter((a) => reasonOf(a) === "SUPERSEDED").length),
    };
  });
}

// ——— 待办 ———

export const listAlarmTodos = (
  q: { mine?: boolean; page?: number; size?: number } = {},
): PageResult<AlarmTodo> =>
  // mine 在 mock 里等价于「OPEN 的都算我的」：没有真实岗位上下文，
  // 造一个假的归属反而会让人以为筛选生效了
  paginate(todos.filter((t) => (q.mine === false ? true : t.status === "OPEN")), q.page, q.size);

export const alarmTodoCount = (): Record<string, number> => ({
  open: todos.filter((t) => t.status === "OPEN").length,
});

export function doneAlarmTodo(todoNo: string, note?: string): AlarmTodo {
  const t = todos.find((x) => x.todoNo === todoNo);
  if (!t) fail(`待办不存在：${todoNo}`, `Todo not found: ${todoNo}`, `المهمة غير موجودة: ${todoNo}`);
  if (t.status !== "OPEN") fail(`待办已是「${t.status}」`, `Todo already ${t.status}`, `المهمة ${t.status}`);
  t.status = "DONE";
  t.doneAt = now();
  t.doneBy = "admin";
  t.doneNote = note ?? null;
  return t;
}

// ——— 摘要 ———

/** 告警码 → 业务域。mock 里按码前缀粗分，真实映射在 `dev_alarm_code.domain`。 */
function domainOf(a: AlarmRecord): string {
  const d = (a as unknown as { domain?: string }).domain;
  if (d) return d;
  return a.alarmCode?.startsWith("E00") ? "AVAILABILITY" : "SERVICE";
}

export function alarmSummary(): AlarmSummary {
  const open = alarms.filter((a) => a.status !== "CLOSED");
  const byDomain: Record<string, AlarmDomainCount> = {};
  for (const a of open) {
    const d = domainOf(a);
    const cur = byDomain[d] ?? { open: 0, critical: 0 };
    byDomain[d] = { open: cur.open + 1, critical: cur.critical + (a.level === "CRITICAL" ? 1 : 0) };
  }
  const today = now().slice(0, 10);
  return {
    byDomain,
    // 已处置但还没关闭：开了工单不等于问题好了，这一格最容易被忽略
    disposedOpen: open.filter((a) => (a as unknown as { disposedAt?: string }).disposedAt).length,
    autoRecoveredToday: alarms.filter(
      (a) => a.status === "CLOSED"
        && (a as unknown as { closeReason?: string }).closeReason === "SELF_HEALED"
        && String((a as unknown as { closedAt?: string }).closedAt ?? "").startsWith(today),
    ).length,
  };
}

export const getAlarmRecord = (alarmNo: string): AlarmRecord => findAlarm(alarmNo);

/** 仅供测试重置。 */
export function __resetTodos(): void {
  todos.length = 0;
  routes.clear();
}

/** 供测试断言 disposition 类型。 */
export type { AlarmDisposition };
