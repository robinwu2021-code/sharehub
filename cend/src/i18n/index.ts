// ar/en 国际化（骨架含关键文案）。RTL 由 locale=ar 时页面根 dir 切换（见 App.vue）。
import { createI18n } from "vue-i18n";
import en from "./en";
import ar from "./ar";

function detectLocale(): "en" | "ar" {
  try {
    const saved = uni.getStorageSync("c-locale");
    if (saved === "en" || saved === "ar") return saved;
    const sys = uni.getSystemInfoSync().language || "en";
    return sys.startsWith("ar") ? "ar" : "en";
  } catch {
    return "en";
  }
}

export const i18n = createI18n({
  legacy: false,
  locale: detectLocale(),
  fallbackLocale: "en",
  messages: { en, ar },
});

export function setLocale(l: "en" | "ar") {
  (i18n.global.locale as unknown as { value: string }).value = l;
  try {
    uni.setStorageSync("c-locale", l);
  } catch {
    /* ignore */
  }
}

export const isRTL = () => (i18n.global.locale as unknown as { value: string }).value === "ar";
