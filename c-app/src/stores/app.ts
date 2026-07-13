// 应用级 store：语言（中/英/阿）+ RTL 方向。切换即时生效并持久化。RTL 仅 ar。
import { defineStore } from "pinia";
import type { Lang } from "@/types";
import { STORAGE, DEFAULT_LANG, LANGS } from "@/shared/constants";
import { i18n, setI18nLang } from "@/i18n";

function isRtlLang(lang: Lang): boolean {
  return LANGS.find((l) => l.id === lang)?.rtl ?? false;
}

function applyDir(lang: Lang) {
  const rtl = isRtlLang(lang);
  // #ifdef H5
  if (typeof document !== "undefined") {
    document.documentElement.setAttribute("dir", rtl ? "rtl" : "ltr");
    document.documentElement.setAttribute("lang", lang);
  }
  // #endif
}

export const useAppStore = defineStore("app", {
  state: () => ({
    lang: (uni.getStorageSync(STORAGE.lang) as Lang) || (DEFAULT_LANG as Lang),
  }),
  getters: {
    isRtl: (s): boolean => isRtlLang(s.lang),
  },
  actions: {
    initLocale() {
      setI18nLang(this.lang);
      applyDir(this.lang);
    },
    setLang(lang: Lang) {
      this.lang = lang;
      uni.setStorageSync(STORAGE.lang, lang);
      setI18nLang(lang);
      applyDir(lang);
    },
  },
});
