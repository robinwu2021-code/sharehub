import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";
import { useLocaleStore } from "@/lib/stores/locale";
import { DEFAULT_CURRENCY } from "./constants";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

const TAG: Record<string, string> = { zh: "zh-CN", en: "en-AE", ar: "ar-AE" };
// 当前 locale 的 Intl tag（非 React 处读 store 快照；随下次渲染生效）。
function localeTag() {
  return TAG[useLocaleStore.getState().locale] ?? "en-AE";
}

/** 金额展示（默认币种见 {@link DEFAULT_CURRENCY}）。随 locale 格式化。 */
export function money(amount: number, currency = DEFAULT_CURRENCY) {
  return new Intl.NumberFormat(localeTag(), { style: "currency", currency }).format(amount ?? 0);
}

/** 时间展示（UTC → 按 locale 简写）。 */
export function fmtTime(iso?: string | null) {
  if (!iso) return "-";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "-" : d.toLocaleString(localeTag());
}
