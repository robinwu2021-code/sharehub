"use client";

// 主题色（白底不变，仅换主色/强调块）。持久化 key = "ops-theme"，
// 与 app/layout.tsx 的首帧脚本 + globals.css 的 [data-theme] 选择器一致。
import { create } from "zustand";
import { persist } from "zustand/middleware";

// 三套皮肤与 C 端 c-app 的 SKINS 一一对应（src/design/tokens.ts）：
// 同名、同 hex，两端截图放一起是同一个产品。
// 皮肤只换 --primary —— 中性色与语义色在 globals.css 里恒定，不参与换肤。
export const THEMES = [
  { key: "mono", label: "黑白灰", color: "#1c2029" },
  { key: "brand", label: "简电青", color: "#17c3c0" },
  { key: "blue", label: "时尚蓝", color: "#2f6bff" },
  { key: "purple", label: "科幻紫", color: "#7c3aed" },
] as const;

export type ThemeKey = (typeof THEMES)[number]["key"];

// 底色方案：与皮肤**正交**的另一个轴。
// raised = 灰画布 + 白卡 + 投影（层次靠明度差）；flat = 纯白画布 + 发丝描边（层次靠线）。
// 不是"把背景改成白"——白底下画布与卡片同色、明度差归零，必须由描边接管分隔职责，
// 所以两套是一组协调的 token 改动，见 globals.css 的 [data-ground] 块。
export const GROUNDS = [
  { key: "raised", label: "灰底分层" },
  { key: "flat", label: "白底描边" },
] as const;
export type GroundKey = (typeof GROUNDS)[number]["key"];
export const DEFAULT_GROUND: GroundKey = "raised";

export function applyGround(key: GroundKey) {
  if (typeof document !== "undefined") document.documentElement.dataset.ground = key;
}
// 运营台默认黑白灰（C 端默认是简电青）：这是两端**刻意的差异**——
// 运营台是密集表格，主色会出现在每个链接/激活态/主按钮上，频率远高于手机端，
// 用彩色主色会显得跳。简电青仍在可选列表里，需要品牌感时可切。
export const DEFAULT_THEME: ThemeKey = "mono";

/** 立即把主题写到 <html data-theme>（供点击时即时生效）。 */
export function applyTheme(key: ThemeKey) {
  if (typeof document !== "undefined") document.documentElement.dataset.theme = key;
}

interface ThemeState {
  themeKey: ThemeKey;
  ground: GroundKey;
  setGround: (g: GroundKey) => void;
  setTheme: (k: ThemeKey) => void;
}

export const useTheme = create<ThemeState>()(
  persist(
    (set) => ({
      themeKey: DEFAULT_THEME,
      ground: DEFAULT_GROUND,
      setGround: (g) => { applyGround(g); set({ ground: g }); },
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
        if (!GROUNDS.some((g) => g.key === state.ground)) state.ground = DEFAULT_GROUND;
        applyTheme(state.themeKey);
        applyGround(state.ground);
      },
    },
  ),
);
