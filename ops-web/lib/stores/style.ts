"use client";

// 风格：换整套 Tier 1 色阶（12 档中性 + 语义色三档），与皮肤正交。
// 皮肤（stores/theme.ts）只换主色；风格决定整个界面的底色与状态色。
// 持久化 key = "ops-style"，与 app/layout.tsx 的首帧脚本 + globals.css 的 [data-style] 选择器一致。
//
// 为什么是两条轴而不是把 ink 做成一套皮肤：设计规范 §2.2 规定皮肤只换主色、
// 「状态的含义不能随皮肤漂移」。风格换的是语义色的**色调**而非**含义**
// （绿仍是正常、红仍是超时），且风格是运营台级的选择，不是个人偏好的配色。
import { create } from "zustand";
import { persist } from "zustand/middleware";
import { DEFAULT_STYLE as DEFAULT_STYLE_VALUE } from "../appearance-defaults";

export const STYLES = [
  { key: "ink", labelKey: "theme.styleInk" },
  { key: "classic", labelKey: "theme.styleClassic" },
] as const;

export type StyleKey = (typeof STYLES)[number]["key"];

// classic 是 2026-09-27 之前的色阶，暂留作对照与回退；
// 验收 ink 后可删 —— 每多一种风格，全状态矩阵的 QA 面就翻一倍。
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
