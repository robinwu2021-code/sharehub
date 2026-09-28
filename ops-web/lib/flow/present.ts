// 展示上的派生规则（只依赖服务给的数据，不按动作代号写死）。
import type { StatusCategory, TicketAction } from "./types";

/**
 * 动作按钮样式：
 * - 走向撤销（CANCELLED）的 → 危险按钮；
 * - 推进大类的（NEW → IN_PROGRESS、→ DONE）→ 主按钮（原型：「接单」「复核」是主按钮）；
 * - 其余（催办、升级、接管这类大类不变的）→ 次按钮。
 */
export function actionVariant(a: TicketAction, current: StatusCategory): "default" | "secondary" | "destructive" {
  if (a.toCategory === "CANCELLED") return "destructive";
  if (a.toCategory !== current) return "default";
  return "secondary";
}

/** 列表里只出「不用填东西」的动作；要填原因 / 字段的进详情办 */
export const inlineActions = (actions: TicketAction[]) =>
  actions.filter((a) => !a.requires.comment && a.requires.fields.length === 0);

const pad = (n: number) => String(n).padStart(2, "0");
/** 09-27 14:02（数字格式三语通用；随语言变的格式化在 lib/flow/i18n.ts 的 fmt） */
export function fmtShort(ms: number) {
  const d = new Date(ms);
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 人名：服务从目录补 displayName，查不到时为空 —— 回落显示 ref（后端 9-28 口径） */
export const who = (m?: { ref: string; displayName?: string } | null) => (m ? m.displayName || m.ref : "");
