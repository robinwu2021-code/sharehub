// vue-i18n（组合式，legacy:false）。中/英/阿 三语，驱动 RTL（方向切换在 stores/app.ts，仅 ar）。
import { createI18n } from "vue-i18n";
import type { Lang } from "@/types";
import { DEFAULT_LANG } from "@/shared/constants";
import zh from "./locale/zh";
import en from "./locale/en";
import ar from "./locale/ar";

export const i18n = createI18n({
  legacy: false,
  globalInjection: true,
  locale: DEFAULT_LANG,
  fallbackLocale: "en",
  messages: { zh, en, ar },
});

export function setI18nLang(lang: Lang) {
  i18n.global.locale.value = lang;
}

// TS 内取文案（模板用 $t）。支持命名插值：t("k", { price })
export const t = (key: string, named?: Record<string, unknown>): string =>
  named ? i18n.global.t(key, named) : i18n.global.t(key);
