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

/** `<StatusBadge map={RES_STATUS} value={r.status} />` */
export function StatusBadge<K extends string>({
  map, value, className,
}: { map: StatusMap<K>; value: K; className?: string }) {
  const s = map[value];
  return <Badge tone={s.tone} className={className}>{s.label}</Badge>;
}

/**
 * 由映射表派生下拉选项，保证「筛选项文案」与「徽标文案」永远一致。
 * 顺序 = 映射表的键序，所以映射表的书写顺序是有意义的（它就是筛选下拉的顺序）。
 */
export function statusOptions<K extends string>(map: StatusMap<K>): { value: string; label: string }[] {
  return (Object.keys(map) as K[]).map((k) => ({ value: k, label: map[k].label }));
}
