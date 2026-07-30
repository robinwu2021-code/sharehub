"use client";

// 主题色（白底不变，仅换主色/强调块）。持久化 key = "ops-theme"，
// 与 app/layout.tsx 的首帧脚本 + globals.css 的 [data-theme] 选择器一致。
import { create } from "zustand";
import { persist } from "zustand/middleware";

// 三套皮肤与 C 端 c-app 的 SKINS 一一对应（src/design/tokens.ts）：
// 同名、同 hex，两端截图放一起是同一个产品。
// 皮肤只换 --primary —— 中性色与语义色在 globals.css 里恒定，不参与换肤。
export const THEMES = [
  { key: "mono", label: "黑白灰", color: "oklch(0.21 0 0)" },
  { key: "blue", label: "时尚蓝", color: "oklch(0.55 0.22 264)" },
  { key: "purple", label: "科幻紫", color: "oklch(0.52 0.26 296)" },
] as const;

export type ThemeKey = (typeof THEMES)[number]["key"];
export const DEFAULT_THEME: ThemeKey = "mono";

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
    {
      name: "ops-theme",
      // 皮肤从 9 套收敛到 3 套（2026-07-30）。老用户 localStorage 里可能还存着
      // indigo/teal/rose 这类已删除的 key —— 不清洗的话选择器会一个都不高亮，
      // 用户以为换肤坏了。这里在水合时把无效值落回默认并同步写回 <html>。
      onRehydrateStorage: () => (state) => {
        if (!state) return;
        const valid = THEMES.some((t) => t.key === state.themeKey);
        if (!valid) state.themeKey = DEFAULT_THEME;
        applyTheme(state.themeKey);
      },
    },
  ),
);
