"use client";

// 主题色（白底不变，仅换主色/强调块）。持久化 key = "ops-theme"，
// 与 app/layout.tsx 的首帧脚本 + globals.css 的 [data-theme] 选择器一致。
import { create } from "zustand";
import { persist } from "zustand/middleware";

export const THEMES = [
  { key: "indigo", label: "靛蓝", color: "oklch(0.55 0.2 268)" },
  { key: "blue", label: "天蓝", color: "oklch(0.57 0.17 245)" },
  { key: "teal", label: "青碧", color: "oklch(0.6 0.11 195)" },
  { key: "emerald", label: "翠绿", color: "oklch(0.6 0.14 162)" },
  { key: "violet", label: "紫罗兰", color: "oklch(0.55 0.22 300)" },
  { key: "rose", label: "玫瑰", color: "oklch(0.6 0.21 15)" },
  { key: "amber", label: "琥珀", color: "oklch(0.66 0.15 62)" },
  { key: "slate", label: "石墨", color: "oklch(0.45 0.035 260)" },
] as const;

export type ThemeKey = (typeof THEMES)[number]["key"];
export const DEFAULT_THEME: ThemeKey = "indigo";

/** 立即把主题写到 <html data-theme>（供点击时即时生效）。 */
export function applyTheme(key: ThemeKey) {
  if (typeof document !== "undefined") document.documentElement.dataset.theme = key;
}

interface ThemeState {
  themeKey: ThemeKey;
  setTheme: (k: ThemeKey) => void;
}

export const useTheme = create<ThemeState>()(
  persist(
    (set) => ({
      themeKey: DEFAULT_THEME,
      setTheme: (k) => {
        applyTheme(k);
        set({ themeKey: k });
      },
    }),
    { name: "ops-theme" },
  ),
);
