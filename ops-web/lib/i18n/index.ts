"use client";

// 轻量 i18n（对齐 ai-kb `useT` 模式）。静态导出 SPA，纯 client 端 catalog，不走路由。
// zh 基准 / en / ar(RTL)。key 用点路径；缺失回退 zh→key。{name} 插值。
import { zh, type Messages } from "./messages/zh";
import { en } from "./messages/en";
import { ar } from "./messages/ar";
import { tNav, tNavNode } from "./nav-labels";
import { useLocaleStore, type Locale } from "@/lib/stores/locale";

export type { Locale } from "@/lib/stores/locale";
export { tNav, tNavNode } from "./nav-labels";
export const LOCALES = ["zh", "en", "ar"] as const;
export const DIR: Record<Locale, "ltr" | "rtl"> = { zh: "ltr", en: "ltr", ar: "rtl" };
export const LOCALE_TAG: Record<Locale, string> = { zh: "zh-CN", en: "en-AE", ar: "ar-AE" };

const CATALOG: Record<Locale, Messages> = { zh, en, ar };

function lookup(obj: unknown, path: string): string | undefined {
  const v = path.split(".").reduce<unknown>((o, k) => (o == null ? undefined : (o as Record<string, unknown>)[k]), obj);
  return typeof v === "string" ? v : undefined;
}

function interpolate(raw: string, params?: Record<string, string | number>): string {
  if (!params) return raw;
  return raw.replace(/\{(\w+)\}/g, (_, k) => (params[k] != null ? String(params[k]) : `{${k}}`));
}

/** 纯函数版翻译（供非 React 处/测试用）。缺失回退 zh，再回退 key 本身。 */
export function translate(locale: Locale, key: string, params?: Record<string, string | number>): string {
  const raw = lookup(CATALOG[locale], key) ?? lookup(CATALOG.zh, key) ?? key;
  return interpolate(raw, params);
}

export type TFn = (key: string, params?: Record<string, string | number>) => string;

export interface I18n {
  locale: Locale;
  dir: "ltr" | "rtl";
  localeTag: string;
  t: TFn;
  tNav: (label: string) => string;
  /** 菜单节点的译名：节点自带的优先（库里那两列），没有才回落 overlay。 */
  tNavNode: (node: { label: string; labelEn?: string; labelAr?: string }) => string;
}

/** React hook：订阅当前 locale，返回 t/tNav/dir/localeTag。 */
export function useI18n(): I18n {
  const locale = useLocaleStore((s) => s.locale);
  return {
    locale,
    dir: DIR[locale],
    localeTag: LOCALE_TAG[locale],
    t: (key, params) => translate(locale, key, params),
    tNav: (label) => tNav(label, locale),
    tNavNode: (node) => tNavNode(node, locale),
  };
}

/** 便捷：仅要 t。 */
export function useT(): TFn {
  return useI18n().t;
}
