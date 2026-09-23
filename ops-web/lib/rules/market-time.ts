// 市场时区：库里存 UTC，界面按「市场时区」显示与输入（运营管理清单 D5）。
//
// 为什么不用浏览器本地时区：运营人员可能在任何地方登录，而「预约调价 20:00 生效」
// 指的是市场当地的 20:00。按浏览器时区换算，同一张调价单在不同电脑上会显示成不同时间。
//
// 纯函数、无 React，只依赖 Intl；时区通过参数传入，单测不受运行机器时区影响。

/** 默认市场时区（阿联酋起步）。多市场时由调用方按市场传入。 */
export const MARKET_TZ = process.env.NEXT_PUBLIC_MARKET_TZ || "Asia/Dubai";

type Parts = { year: number; month: number; day: number; hour: number; minute: number; second: number };

function partsIn(date: Date, tz: string): Parts {
  const fmt = new Intl.DateTimeFormat("en-US", {
    timeZone: tz, hourCycle: "h23",
    year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit",
  });
  const get = (t: string) => Number(fmt.formatToParts(date).find((p) => p.type === t)?.value);
  return { year: get("year"), month: get("month"), day: get("day"), hour: get("hour"), minute: get("minute"), second: get("second") };
}

/** 该时刻在 tz 下相对 UTC 的偏移（分钟，东区为正）。 */
export function tzOffsetMinutes(date: Date, tz: string = MARKET_TZ): number {
  const p = partsIn(date, tz);
  const asUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second);
  return Math.round((asUtc - Math.floor(date.getTime() / 1000) * 1000) / 60000);
}

/** 偏移显示为 `UTC+4` / `UTC+5:30` / `UTC-3`。 */
export function formatOffset(minutes: number): string {
  const sign = minutes < 0 ? "-" : "+";
  const abs = Math.abs(minutes);
  const h = Math.floor(abs / 60);
  const m = abs % 60;
  return `UTC${sign}${h}${m ? `:${String(m).padStart(2, "0")}` : ""}`;
}

const pad = (n: number) => String(n).padStart(2, "0");

/**
 * UTC ISO 串 → 市场时区的 `YYYY-MM-DD HH:mm`。
 * 空值或非法时间返回空串（列表里显示为空，不抛错）。
 */
export function formatMarketTime(iso: string | null | undefined, tz: string = MARKET_TZ): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const p = partsIn(d, tz);
  return `${p.year}-${pad(p.month)}-${pad(p.day)} ${pad(p.hour)}:${pad(p.minute)}`;
}

/** 同 formatMarketTime，并附上偏移标注：`2026-09-22 20:00 (UTC+4)`。 */
export function formatMarketTimeWithZone(iso: string | null | undefined, tz: string = MARKET_TZ): string {
  const s = formatMarketTime(iso, tz);
  return s ? `${s} (${formatOffset(tzOffsetMinutes(new Date(iso as string), tz))})` : "";
}

/**
 * 市场时区的本地时间（`YYYY-MM-DD HH:mm` 或 `YYYY-MM-DDTHH:mm`）→ UTC ISO 串。
 *
 * 做法：先把输入当 UTC 得到一个近似时刻，取该时刻在 tz 的偏移修正一次；
 * 再用修正后的时刻复核偏移（跨夏令时切换点时两次偏移不同，以第二次为准）。
 * 中东市场无夏令时，但保留复核，避免将来加市场时静默算错。
 */
export function marketLocalToUtcIso(local: string, tz: string = MARKET_TZ): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?$/.exec(local.trim());
  if (!m) throw new Error(`时间格式应为 YYYY-MM-DD HH:mm：${local}`);
  const [, y, mo, d, h, mi, s] = m;
  const naive = Date.UTC(+y, +mo - 1, +d, +h, +mi, s ? +s : 0);
  let ts = naive - tzOffsetMinutes(new Date(naive), tz) * 60000;
  ts = naive - tzOffsetMinutes(new Date(ts), tz) * 60000;
  return new Date(ts).toISOString();
}

/** 当前时刻在市场时区的 `YYYY-MM-DDTHH:mm`（给 datetime-local 输入框作默认值/下限）。 */
export function marketNowLocal(now: Date = new Date(), tz: string = MARKET_TZ): string {
  return formatMarketTime(now.toISOString(), tz).replace(" ", "T");
}
