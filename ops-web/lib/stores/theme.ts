"use client";

// 主题色（白底不变，仅换主色/强调块）。持久化 key = "ops-theme"，
// 与 app/layout.tsx 的首帧脚本 + globals.css 的 [data-theme] 选择器一致。
import { create } from "zustand";
import { persist } from "zustand/middleware";
import { DEFAULT_THEME as DEFAULT_THEME_VALUE } from "../appearance-defaults";

// 前四套皮肤与 C 端 c-app 的 SKINS 一一对应（src/design/tokens.ts）：
// 同名、同 hex，两端截图放一起是同一个产品。
// `teal`（墨青）是 2026-09-27 为运营台新增的，取自流程与工单原型，**C 端暂无对应** ——
// 这是有意的例外：它和 ink 风格一起构成运营台的默认观感。
// 皮肤只换 --primary —— 中性色与语义色由「风格」（stores/style.ts）决定，不参与换肤。
export const THEMES = [
  { key: "mono", label: "黑白灰", color: "#1c2029" },
  { key: "brand", label: "简电青", color: "#17c3c0" },
  { key: "blue", label: "时尚蓝", color: "#2f6bff" },
  { key: "purple", label: "科幻紫", color: "#7c3aed" },
  { key: "teal", label: "墨青", color: "#0f7173" },
] as const;

export type ThemeKey = (typeof THEMES)[number]["key"];

// 曾经有过一个 data-ground 轴（灰底 / 全白底）。**已移除**：
// 布局定案为"两张白纸放在浅灰桌面上"后，全白底与它直接矛盾——
// 画布若也是白的，两纸之间那道缝就不存在了，分隔手段随之失效。
// 现在只有一套体系：page 是唯一的非白面，导航与内容都是白纸。
// 运营台默认墨青（2026-09-27 起；此前是黑白灰）。
// 当初不用彩色主色做默认，理由是「主色出现在每个链接/激活态/主按钮上，彩色会显跳」——
// 那次被否的是简电青 #17C3C0（高明度高饱和）。墨青 #0f7173 明度低、饱和度低，
// 读起来接近墨色，不重复那个问题；且它是原型观感的主要来源。
// 已存过皮肤的老用户保持原选择，只有新用户拿到默认值。
export const DEFAULT_THEME: ThemeKey = DEFAULT_THEME_VALUE;

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
      // indigo/rose 这类已删除的 key（teal 已于 2026-09-27 以新色值恢复，旧值会直接生效） —— 不清洗的话选择器会一个都不高亮，
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
