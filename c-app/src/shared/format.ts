// 本地化格式化：AED 货币 / 距离。集中管理，避免各页面散落拼接。
import { CURRENCY } from "./constants";

export function money(amount: number, currency = CURRENCY): string {
  return `${currency} ${amount.toFixed(2)}`;
}

/**
 * 距离。**null = 算不出**（没给定位或站点没录坐标），返回空串，由调用点决定不渲染那一段。
 * 不要把 null 当 0 —— 界面上「0 m」读起来是「你就站在店里」。
 */
export function distance(m: number | null): string {
  if (m == null) return "";
  return m >= 1000 ? `${(m / 1000).toFixed(1)} km` : `${Math.round(m)} m`;
}

export function timeOf(iso: string): string {
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}`;
}

// 短日期时间：MM-DD HH:mm（钱包流水等列表）
export function dateTimeOf(iso: string): string {
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
