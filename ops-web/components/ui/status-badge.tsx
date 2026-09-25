// 「枚举 → 徽标」的统一渲染件（组合层）。
//
// 各页都有形如 `const XX_STATUS: Record<Status, { label, tone }>` 的映射表，
// 原先每处都要自己写一遍 tone 的字面量联合，且渲染时手抄
// `<Badge tone={M[v].tone}>{M[v].label}</Badge>`。这里只收敛**类型与渲染**：
// 映射表本身是业务语义（哪个状态该是什么色、叫什么名），留在各自页面。
import { Badge, type BadgeTone } from "./badge";

/**
 * 枚举状态的展示映射：值 → 文案 + 色调。
 * 用法（保持声明在页面里，它是该页的业务语义）：
 *   const RES_STATUS: StatusMap<Reservation["status"]> = {
 *     PENDING: { label: "待履约", tone: "warning" }, ...
 *   };
 */
export type StatusMap<K extends string> = Record<K, { label: string; tone: BadgeTone }>;

/**
 * `<StatusBadge map={RES_STATUS} value={r.status} />`
 *
 * <h3>映射不到时降级显示，不抛</h3>
 * 原来是 `map[value].tone` —— 后端给一个映射表里没有的值，这里就 TypeError，
 * 而它在表格 cell 里，于是**整页白屏**。36 个文件、181 处用到本组件，
 * 任何一处枚举与后端对不上都能把那一页干掉，且 TS 拦不住（`K` 是编译期契约，
 * 真实响应是运行时数据）。实跑对着真后端遇到过：告警等级前端认 INFO/WARN/CRITICAL，
 * 库里存的是 HIGH（种子把工单优先级词表写进了告警等级），/alarms 直接白屏。
 *
 * 降级成「原样显示这个值 + 中性色」：页面还能用，而那个没见过的值**明晃晃摆在界面上**，
 * 比白屏好查得多 —— 白屏只告诉你「坏了」，这个告诉你「坏在哪个值上」。
 */
export function StatusBadge<K extends string>({
  map, value, className,
}: { map: StatusMap<K>; value: K; className?: string }) {
  const s = map[value];
  if (!s) {
    return <Badge tone="outline" className={className}>{String(value ?? "-")}</Badge>;
  }
  return <Badge tone={s.tone} className={className}>{s.label}</Badge>;
}

/**
 * 由映射表派生下拉选项，保证「筛选项文案」与「徽标文案」永远一致。
 * 顺序 = 映射表的键序，所以映射表的书写顺序是有意义的（它就是筛选下拉的顺序）。
 */
export function statusOptions<K extends string>(map: StatusMap<K>): { value: string; label: string }[] {
  return (Object.keys(map) as K[]).map((k) => ({ value: k, label: map[k].label }));
}
