// UnoCSS 配置：preset-wind 兼容原子类（与运营端 Tailwind 心智一致，ADR-008）。
// unocss-applet 的 presetApplet + presetRemRpx，同一套类名在 H5 / App / 小程序都可用。
// 关键：主题色映射到 CSS 变量 var(--pb-*) → 换肤(改根节点 data-skin/data-theme)即全局生效，无需重编译。
import { defineConfig, transformerDirectives, transformerVariantGroup } from "unocss";
import { presetApplet, presetRemRpx, transformerAttributify } from "unocss-applet";

export default defineConfig({
  presets: [presetApplet(), presetRemRpx({ baseFontSize: 16 })],
  transformers: [
    transformerDirectives(),
    transformerVariantGroup(),
    transformerAttributify({ prefixedOnly: true }),
  ],
  theme: {
    colors: {
      primary: "var(--pb-primary)",
      "on-primary": "var(--pb-on-primary)",
      "primary-tint": "var(--pb-primary-tint)",
      success: "var(--pb-success)",
      "success-tint": "var(--pb-success-tint)",
      warning: "var(--pb-warning)",
      "warning-tint": "var(--pb-warning-tint)",
      danger: "var(--pb-danger)",
      "danger-tint": "var(--pb-danger-tint)",
      bg: "var(--pb-bg)",
      surface: "var(--pb-surface)",
      elev: "var(--pb-elev)",
      ink: "var(--pb-ink)",
      sub: "var(--pb-sub)",
      faint: "var(--pb-faint)",
    },
  },
});
