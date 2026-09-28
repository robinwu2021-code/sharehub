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

/**
 * 取文案，并自己做 `{name}` 插值。
 *
 * <b>为什么不交给 vue-i18n</b>：uni 的构建把 vue-i18n 解析到 **runtime-only** 包，
 * 消息编译器被整个 tree-shake 掉（产物里连解析器的错误码都搜不到）。
 * 它不报错，只是把原文吐回来 —— 于是原生包里「已转维修工单 {no}」「券还剩 {n} 张」
 * 全都带着花括号显示给用户看（2026-09-28 模拟器实测，中文/阿语都一样）。
 * 官方解法是构建期预编译消息（`@intlify/unplugin-vue-i18n`），但那要改消息的组织方式；
 * 本端只用到具名参数这一种能力，自己替换更小也更可预期，三端行为一致。
 *
 * 缺参数时保留原样的 `{name}`，不静默替成空串 —— 空串会让「有效期至 」这种残句看起来像文案写错。
 */
export const t = (key: string, named?: Record<string, unknown>): string => {
  const raw = i18n.global.t(key);
  if (!named) return raw;
  return raw.replace(/\{(\w+)\}/g, (all, name: string) =>
    named[name] == null ? all : String(named[name]),
  );
};
