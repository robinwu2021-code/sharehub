"use client";

// 导航偏好（AC2）：L3 呈现模式 panel|miller + Rail 展开态，localStorage 持久化。
// AppShell 在 hydration 后才渲染导航（ready 门），无 SSR 不一致问题。
import { create } from "zustand";
import { persist } from "zustand/middleware";
import { NAV_MODE_DEFAULT, NAV_PREFS_STORAGE_KEY, type NavMode } from "@/lib/nav";

interface NavPrefs {
  navMode: NavMode;
  railExpanded: boolean;
  setNavMode: (m: NavMode) => void;
  toggleRail: () => void;
}

export const useNavPrefs = create<NavPrefs>()(
  persist(
    (set) => ({
      navMode: NAV_MODE_DEFAULT,
      railExpanded: false,
      setNavMode: (m) => set({ navMode: m }),
      toggleRail: () => set((s) => ({ railExpanded: !s.railExpanded })),
    }),
    { name: NAV_PREFS_STORAGE_KEY },
  ),
);
