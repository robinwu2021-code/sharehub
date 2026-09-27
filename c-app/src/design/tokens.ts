// 主题 token 系统「目录」。皮肤(色)与明暗(风格)通过 CSS 变量切换，实际变量值定义在 App.vue 全局样式，
// UnoCSS 主题色指向 var(--pb-*)（uno.config.ts），故换肤 = 改根节点 data-skin/data-theme = 全局即时生效。
// 扁平色块设计：主色 + 语义色 + 中性面色 + 由 color-mix 派生的 tint（色块底），几乎不用线条。

export type SkinId = "mono" | "blue" | "purple";
export type ModeId = "light" | "dark";

export interface Labeled {
  zh: string;
  en: string;
  ar: string;
}
export interface SkinDef {
  id: SkinId;
  color: string; // 选择器上的预览色（= light 下主色）
  label: Labeled;
}

export const SKINS: SkinDef[] = [
  { id: "mono", color: "#18181B", label: { zh: "黑白灰", en: "Mono", ar: "رمادي" } },
  { id: "blue", color: "#2F6BFF", label: { zh: "时尚蓝", en: "Blue", ar: "أزرق" } },
  { id: "purple", color: "#7C3AED", label: { zh: "科幻紫", en: "Purple", ar: "بنفسجي" } },
];

export const MODES: { id: ModeId; label: Labeled }[] = [
  { id: "light", label: { zh: "浅色", en: "Light", ar: "فاتح" } },
  { id: "dark", label: { zh: "深色", en: "Dark", ar: "داكن" } },
];

export const DEFAULT_SKIN: SkinId = "mono";
export const DEFAULT_MODE: ModeId = "light";

// 圆角/间距（rpx），扁平风偏大圆角
export const radius = { sm: "12rpx", md: "20rpx", lg: "28rpx", full: "9999px" } as const;

/**
 * 小程序图标色（pb-icon 的 color）。小程序的图标是 CSS 背景图，拿不到 currentColor，
 * 靠父元素 class 变色（收藏变红、深底反白）在那里一律失效 —— 颜色要显式传。
 * 取值 = App.vue 默认皮肤（mono · 浅色）的 --pb-ink / --pb-sub / --pb-danger / --pb-on-primary，改那边要同步。
 * App / H5 不读这个值（仍跟 currentColor 走，随皮肤明暗变化）。
 */
export const MP_ICON = {
  ink: "#16171d",
  sub: "#8a8d97",
  /** 暗色下的墨色 / 次要色（= App.vue 暗色块的 --pb-ink / --pb-sub）。pb-icon 默认色随明暗切换 */
  inkDark: "#f2f4f7",
  subDark: "#97a0af",
  danger: "#f04438",
  onPrimary: "#ffffff",
} as const;

/**
 * 系统导航栏（手势条那一段）背后的颜色 = 底栏的面色（App.vue 的 --pb-surface，浅 / 暗）。
 * targetSdk 35 起 Android 15+ 强制边到边，导航栏透明，露出来的是窗口底色 —— 默认黑色。
 * 顶部状态栏后面是页面的白，底部却是一条黑：屏幕上下两端的圆角看起来就不一样（真机实测）。
 */
export const NAV_BAR_BG = { light: "#ffffff", dark: "#161a22" } as const;
