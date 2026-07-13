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
