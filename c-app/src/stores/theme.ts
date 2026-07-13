// 主题 store：皮肤(色) + 明暗(风格)。切换即时全局生效（改根节点 data-skin/data-theme），并持久化。
import { defineStore } from "pinia";
import type { SkinId, ModeId } from "@/design/tokens";
import { DEFAULT_SKIN, DEFAULT_MODE } from "@/design/tokens";
import { STORAGE } from "@/shared/constants";

function applyTheme(skin: SkinId, mode: ModeId) {
  // #ifdef H5
  if (typeof document !== "undefined") {
    document.documentElement.setAttribute("data-skin", skin);
    document.documentElement.setAttribute("data-theme", mode);
  }
  // #endif
}

export const useThemeStore = defineStore("theme", {
  state: () => ({
    skin: (uni.getStorageSync(STORAGE.skin) as SkinId) || DEFAULT_SKIN,
    mode: (uni.getStorageSync(STORAGE.mode) as ModeId) || DEFAULT_MODE,
  }),
  getters: {
    isDark: (s): boolean => s.mode === "dark",
  },
  actions: {
    init() {
      applyTheme(this.skin, this.mode);
    },
    setSkin(skin: SkinId) {
      this.skin = skin;
      uni.setStorageSync(STORAGE.skin, skin);
      applyTheme(this.skin, this.mode);
    },
    setMode(mode: ModeId) {
      this.mode = mode;
      uni.setStorageSync(STORAGE.mode, mode);
      applyTheme(this.skin, this.mode);
    },
    toggleMode() {
      this.setMode(this.mode === "dark" ? "light" : "dark");
    },
  },
});
