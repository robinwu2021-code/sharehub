"use client";

// 风格：换整体 UI 形态（表格、tab、徽章、卡片、按钮、输入框……），与皮肤正交。
// 皮肤（stores/theme.ts）只换主色；风格不带色相，所以任何风格 × 任何主题色都成立。
// 持久化 key = "ops-style"，与 app/layout.tsx 的首帧脚本 + globals.css 的 [data-style] 选择器一致。
//
// 为什么是两条轴：颜色（主色）是个人偏好，形态是运营台级的选择 —— 两者独立变化。
// 风格唯一动的颜色是语义色的色调（降饱和以过 AA），不改含义：绿仍是正常、红仍是超时。
import { create } from "zustand";
import { persist } from "zustand/middleware";
import { DEFAULT_STYLE as DEFAULT_STYLE_VALUE } from "../appearance-defaults";

export const STYLES = [
  { key: "clean", labelKey: "theme.styleClean" },
  { key: "classic", labelKey: "theme.styleClassic" },
] as const;

export type StyleKey = (typeof STYLES)[number]["key"];

// classic 是 2026-09-27 之前的形态，暂留作对照与回退；
// 验收 clean 后可删 —— 每多一种风格，全状态矩阵的 QA 面就翻一倍。
// 老用户存储里的 "ink"（2026-09-27 当天的旧名）在水合时落回默认值 clean。
export const DEFAULT_STYLE: StyleKey = DEFAULT_STYLE_VALUE;

/** 立即把风格写到 <html data-style>。 */
export function applyStyle(key: StyleKey) {
  if (typeof document !== "undefined") document.documentElement.dataset.style = key;
}

interface StyleState {
  styleKey: StyleKey;
  setStyle: (k: StyleKey) => void;
}

export const useStyle = create<StyleState>()(
  persist(
    (set) => ({
      styleKey: DEFAULT_STYLE,
      setStyle: (k) => {
        applyStyle(k);
        set({ styleKey: k });
      },
    }),
    {
      name: "ops-style",
      // 同 theme.ts：存储里的无效值落回默认并写回 <html>，否则选择器一个都不高亮。
      onRehydrateStorage: () => (state) => {
        if (!state) return;
        if (!STYLES.some((s) => s.key === state.styleKey)) state.styleKey = DEFAULT_STYLE;
        applyStyle(state.styleKey);
      },
    },
  ),
);
