"use client";

// 主题色（白底不变，仅换主色/强调块）。持久化 key = "ops-theme"，
// 与 app/layout.tsx 的首帧脚本 + globals.css 的 [data-theme] 选择器一致。
import { create } from "zustand";
import { persist } from "zustand/middleware";
import { DEFAULT_THEME as DEFAULT_THEME_VALUE } from "../appearance-defaults";

// 前四套皮肤与 C 端 c-app 的 SKINS 一一对应（src/design/tokens.ts）：
// 同名、同 hex，两端截图放一起是同一个产品。
// `teal`（墨青，2026-09-27）与 navy / indigo / forest / mocha（2026-09-28）是运营台独有的，**C 端暂无对应**。
// 后四套是用户要的「运营高端常用、耐看」的颜色：低明度低饱和（主色出现在每个链接 / 选中态 / 主按钮上，
// 高饱和会显跳），并**避开语义色相**（红 = 超时 / 失败、琥珀 = 将超时），所以没有酒红、金色。
// 皮肤只换 --primary —— 中性色与语义色由「风格」（stores/style.ts）决定，不参与换肤。
export const THEMES = [
  { key: "mono", label: "黑白灰", color: "#1c2029" },
  { key: "brand", label: "简电青", color: "#17c3c0" },
  { key: "blue", label: "时尚蓝", color: "#2e6aff" },
  { key: "purple", label: "科幻紫", color: "#7c3aed" },
  { key: "teal", label: "墨青", color: "#0f7173" },
  { key: "navy", label: "藏青", color: "#1d3557" },
  { key: "indigo", label: "靛蓝", color: "#4a55c8" },
  { key: "forest", label: "墨绿", color: "#1e5b46" },
  { key: "mocha", label: "摩卡", color: "#6b4e3d" },
] as const;

export type ThemeKey = (typeof THEMES)[number]["key"];

// 曾经有过一个 data-ground 轴（灰底 / 全白底）。**已移除**：
// 布局定案为"两张白纸放在浅灰桌面上"后，全白底与它直接矛盾——
// 画布若也是白的，两纸之间那道缝就不存在了，分隔手段随之失效。
// 现在只有一套体系：page 是唯一的非白面，导航与内容都是白纸。
// 运营台默认**黑白灰**（2026-09-28 用户定：「颜色默认尽量用黑白灰」）。9-27 曾改为墨青（原型主色），
// 现回到黑白灰：原型的观感主要来自明暗结构与线条（规范 §2.6 #13/#14），而不是主色。
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
