"use client";

// 语言偏好：zh/en/ar，localStorage 持久化（key `ops-locale`）。
// 与 app/layout.tsx 首帧脚本一致：把 lang/dir 写到 <html>。
import { create } from "zustand";
import { persist } from "zustand/middleware";

export type Locale = "zh" | "en" | "ar";
export const LOCALE_DIR: Record<Locale, "ltr" | "rtl"> = { zh: "ltr", en: "ltr", ar: "rtl" };
export const DEFAULT_LOCALE: Locale = "zh";
export const LOCALE_STORAGE_KEY = "ops-locale";

/** 即时把 locale 写到 <html lang dir>（点击切换时生效）。 */
export function applyLocale(locale: Locale) {
  if (typeof document === "undefined") return;
  document.documentElement.lang = locale === "zh" ? "zh-CN" : locale;
  document.documentElement.dir = LOCALE_DIR[locale];
}

interface LocaleState {
  locale: Locale;
  setLocale: (l: Locale) => void;
}

export const useLocaleStore = create<LocaleState>()(
  persist(
    (set) => ({
      locale: DEFAULT_LOCALE,
      setLocale: (l) => {
        applyLocale(l);
        set({ locale: l });
      },
    }),
    { name: LOCALE_STORAGE_KEY },
  ),
);
