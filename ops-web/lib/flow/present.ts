// 展示上的派生规则（只依赖服务给的数据，不按动作代号写死）。
import type { StatusCategory, TicketAction } from "./types";
import { isValidationError } from "./errors";

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
/** 09-27 14:02 */
export function fmtShort(ms: number) {
  const d = new Date(ms);
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
/** 今天显示 14:02，其余显示 09-27 14:02 */
export function fmtWhen(ms: number, now: number = Date.now()) {
  const d = new Date(ms), n = new Date(now);
  const sameDay = d.toDateString() === n.toDateString();
  return sameDay ? `今天 ${pad(d.getHours())}:${pad(d.getMinutes())}` : fmtShort(ms);
}

/** 失败提示：422 时把逐条错误拼进去（表单抽屉没有按服务端路径挂错误的位置） */
export function failText(e: Error): string {
  return isValidationError(e) ? `${e.message}：${e.errors.map((x) => x.error).join("；")}` : e.message;
}

/** 分钟 → 「30 分钟」「2 小时」「1 天」 */
export function fmtSpan(min: number): string {
  if (min % 1440 === 0) return `${min / 1440} 天`;
  if (min % 60 === 0) return `${min / 60} 小时`;
  return `${min} 分钟`;
}
