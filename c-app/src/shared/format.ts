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

/**
 * 解析服务端时间戳。**按 UTC 解**，不是按设备本地时区。
 *
 * 后端 `rentStartAt` 是 `LocalDateTime.now(ZoneOffset.UTC).toString()` ——
 * 「UTC 的时刻，却没带 Z」。JS 对这种无偏移量的 ISO 日期时间按**本地时区**解，
 * 于是在迪拜（+4）算出来的已用时长比真实少 4 小时、在上海（+8）少 8 小时，
 * 刚借出的单会显示成负数。mock 的 `fmtNow()` 走 `toISOString()`，同样是 UTC，口径一致。
 *
 * ⚠️ 这个函数**只适用于确定是 UTC 的字段**（目前：`rentStartAt` / `rentEndAt`）。
 * 状态时间线的 `at` 来自 `ord_event_log.created_at`，那是数据库生成的、跟着服务器时区走
 * （生产是 Asia/Shanghai），与本字段**不是同一个时区** —— 同一个响应里两种口径。
 * 所以别把这个解析器顺手套到 `timeline[].at` 上：那只是把一种错换成另一种错。
 * 统一口径要在后端做（见「三个时区」那批待办）。
 */
export function parseUtc(s: string | null): Date | null {
  if (!s) return null;
  // "2026-09-27 01:23:45"（mock）与 "2026-09-27T01:23:45.678"（后端）两种写法都收
  const iso = s.trim().replace(" ", "T");
  const d = new Date(/([Zz]|[+-]\d{2}:?\d{2})$/.test(iso) ? iso : iso + "Z");
  return Number.isNaN(d.getTime()) ? null : d;
}

/**
 * 已用分钟数（端上按本地时钟推算，用于进行中订单的计时展示）。
 *
 * **只算时长，不算钱。** 计费口径在服务端（封顶、免费时长、权益、券都在那边），
 * 端上乘一个每小时单价看着很近，但它与结算不是同一套规则 —— 那种「差一点」的数字
 * 比不显示更糟：用户会拿它去对账单。
 *
 * 取不到起始时间返回 null（由调用点决定不渲染那一段，别显示 0）。
 */
export function elapsedMin(startAt: string | null): number | null {
  const d = parseUtc(startAt);
  if (!d) return null;
  return Math.max(0, Math.floor((Date.now() - d.getTime()) / 60000));
}

/** 时长 → "2h 15m" / "15m"。 */
export function durationText(min: number): string {
  const h = Math.floor(min / 60);
  return h > 0 ? `${h}h ${min % 60}m` : `${min}m`;
}
