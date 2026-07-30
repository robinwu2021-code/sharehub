"use client";

// 导航偏好：Rail 展开态，localStorage 持久化。
// AppShell 在 hydration 后才渲染导航（ready 门），无 SSR 不一致问题。
// 2026-07-30 删 navMode：L3 呈现只剩 panel 一种（miller 三列逐级已下线），
// 只有单个取值的枚举没有存在意义。老 localStorage 里可能残留 navMode，
// 由 version/migrate 显式丢弃 —— persist 默认的浅合并会把它带回 state
// （类型上并不存在的键），虽不报错，却会一直躺在存储里。
import { create } from "zustand";
import { persist } from "zustand/middleware";
import { NAV_PREFS_STORAGE_KEY } from "@/lib/nav";

interface NavPrefs {
  railExpanded: boolean;
  toggleRail: () => void;
}

export const useNavPrefs = create<NavPrefs>()(
  persist(
    (set) => ({
      railExpanded: false,
      toggleRail: () => set((s) => ({ railExpanded: !s.railExpanded })),
    }),
    {
      name: NAV_PREFS_STORAGE_KEY,
      version: 1,
      // v0（含 navMode）→ v1：只认 railExpanded，其余键丢弃。
      migrate: (persisted) => ({
        railExpanded: Boolean((persisted as { railExpanded?: unknown } | null)?.railExpanded),
      }),
      // 只写回 railExpanded：光靠 migrate 不够 —— 实测老 blob 里的 navMode 仍会
      // 经默认浅合并回到 state 并被下一次写盘带上，partialize 才真正把它清掉。
      partialize: (s) => ({ railExpanded: s.railExpanded }) as NavPrefs,
    },
  ),
);
