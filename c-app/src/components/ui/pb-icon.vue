<script setup lang="ts">
// 描边线性图标（currentColor 跟随文字色，随皮肤/明暗变化）。替代 emoji，统一精致度。
// H5/App(WebView) 用 v-html 注入 SVG。
// 小程序不支持 v-html —— 此前小程序里**所有图标都不显示**（微信开发者工具实测）。
// 小程序端改成 CSS 背景图（data URI）；背景图里拿不到 currentColor，颜色用 color（默认墨色）。
import { computed } from "vue";
import { useThemeStore } from "@/stores/theme";
import { useAppStore } from "@/stores/app";
import { MP_ICON } from "@/design/tokens";

const props = withDefaults(defineProps<{ name: string; size?: number; stroke?: number; color?: string }>(), {
  size: 40,
  stroke: 2,
  color: undefined, // 不传 = 跟随明暗的墨色（MP_ICON.ink / inkDark）
});
const theme = useThemeStore();

// Lucide 风格 24x24 路径
const P: Record<string, string> = {
  search: '<circle cx="11" cy="11" r="7.5"/><path d="m21 21-4.3-4.3"/>',
  zap: '<path d="M13 2 4 13.5h7L10 22l9-11.5h-7L13 2Z"/>',
  // 扫码：四角取景框 + 中间扫描线。首页那个主按钮此前用的是 zap（闪电），
  // 读起来是「快充」而不是「扫一扫」——图标与文案不是一回事。
  // ⚠️ 路径一律用大写 M 起头：小写工具前缀字母紧跟小数（如 m9.5）会被 UnoCSS
  // applet transformer 当成 margin 工具类改写，图标在生产构建里直接不显示（已踩过）。
  scan: '<path d="M3 8V5.5A2.5 2.5 0 0 1 5.5 3H8"/><path d="M16 3h2.5A2.5 2.5 0 0 1 21 5.5V8"/><path d="M21 16v2.5A2.5 2.5 0 0 1 18.5 21H16"/><path d="M8 21H5.5A2.5 2.5 0 0 1 3 18.5V16"/><path d="M3 12h18"/>',
  keyboard: '<rect x="2.5" y="6" width="19" height="12" rx="2.5"/><path d="M7 10h0M11 10h0M15 10h0M8 14h8"/>',
  navigation: '<path d="M3 11 22 2l-9 19-2-8-8-2Z"/>',
  pin: '<path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z"/><circle cx="12" cy="10" r="2.6"/>',
  ticket: '<path d="M3 9a2.5 2.5 0 0 0 0 6v1.5A1.5 1.5 0 0 0 4.5 18h15a1.5 1.5 0 0 0 1.5-1.5V15a2.5 2.5 0 0 1 0-6V7.5A1.5 1.5 0 0 0 19.5 6h-15A1.5 1.5 0 0 0 3 7.5Z"/><path d="M13 6v2M13 15v2M13 11v1"/>',
  card: '<rect x="2.5" y="5" width="19" height="14" rx="2.5"/><path d="M2.5 9.5h19"/>',
  sparkles: '<path d="m12 3-1.7 5.1a2 2 0 0 1-1.2 1.2L4 11l5.1 1.7a2 2 0 0 1 1.2 1.2L12 19l1.7-5.1a2 2 0 0 1 1.2-1.2L20 11l-5.1-1.7a2 2 0 0 1-1.2-1.2Z"/>',
  user: '<circle cx="12" cy="8" r="3.6"/><path d="M6 20v-1a5 5 0 0 1 5-5h2a5 5 0 0 1 5 5v1"/>',
  receipt: '<path d="M5 3v18l2-1 2 1 2-1 2 1 2-1 2 1V3l-2 1-2-1-2 1-2-1-2 1-2-1Z"/><path d="M9 8h6M9 12h6M9 16h4"/>',
  battery: '<rect x="2.5" y="8" width="15" height="8" rx="2"/><path d="M20.5 11v3"/><path d="M6.5 11v2"/>',
  clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5v5l3 1.7"/>',
  check: '<path d="M20 6.5 9.5 17.5 4.5 12.5"/>',
  chevron: '<path d="m9 6 6 6-6 6"/>',
  wallet: '<path d="M20 8V6.5A1.5 1.5 0 0 0 18.5 5H5.5A1.5 1.5 0 0 0 4 6.5v11A1.5 1.5 0 0 0 5.5 19h13a1.5 1.5 0 0 0 1.5-1.5V16"/><path d="M15 12h6v-4h-6a2 2 0 0 0 0 4Z"/>',
  shield: '<path d="M12 22s8-4 8-10V5.5l-8-3-8 3V12c0 6 8 10 8 10Z"/>',
  gauge: '<path d="M12 14a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z"/><path d="M13.4 11.6 17 8"/><path d="M4 18a9 9 0 1 1 16 0"/>',
  bell: '<path d="M6 8.5a6 6 0 0 1 12 0c0 6 2.5 7.5 2.5 7.5h-17S6 14.5 6 8.5"/><path d="M10.3 20a1.9 1.9 0 0 0 3.4 0"/>',
  edit: '<path d="M12 20h8"/><path d="M16.5 3.5a2 2 0 0 1 3 3L8 18l-4 1 1-4Z"/>',
  lock: '<rect x="5" y="11" width="14" height="9.5" rx="2.2"/><path d="M8 11V7.5a4 4 0 0 1 8 0V11"/>',
  mail: '<rect x="2.5" y="5" width="19" height="14" rx="2.2"/><path d="M3.5 6.5l8.5 6 8.5-6"/>',
  phone: '<path d="M5.5 4h3.5l1.8 4.5-2.3 1.4a11 11 0 0 0 5 5l1.4-2.3L19.5 15v3.5a2 2 0 0 1-2 2A15 15 0 0 1 3.5 6a2 2 0 0 1 2-2Z"/>',
  globe: '<circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17"/><path d="M12 3.5a14 14 0 0 1 0 17a14 14 0 0 1 0-17"/>',
  logout: '<path d="M9 21H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3"/><path d="m16 16 4-4-4-4"/><path d="M20 12H9"/>',
  heart: '<path d="M12 20.5s-7.5-4.7-7.5-10A4.2 4.2 0 0 1 12 8a4.2 4.2 0 0 1 7.5 2.5c0 5.3-7.5 10-7.5 10Z"/>',
  camera: '<path d="M14.5 4.5h-5L7.5 7h-3a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h15a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3Z"/><circle cx="12" cy="13" r="3"/>',
  message: '<path d="M21 14.5a2 2 0 0 1-2 2H8l-4 3.5V5a2 2 0 0 1 2-2h13a2 2 0 0 1 2 2Z"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  trash: '<path d="M4.5 7h15"/><path d="M9 7V5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2"/><path d="M6.5 7l1 12.5a2 2 0 0 0 2 1.9h5a2 2 0 0 0 2-1.9L18 7"/>',
  info: '<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5"/><path d="M12 8h.01"/>',
  map: '<path d="M9 4 3.5 6v14L9 18l6 2 5.5-2V4L15 6 9 4Z"/><path d="M9 4v14"/><path d="M15 6v14"/>',
};

const svg = computed(
  () =>
    `<svg viewBox="0 0 24 24" width="100%" height="100%" fill="none" stroke="currentColor" stroke-width="${props.stroke}" stroke-linecap="round" stroke-linejoin="round">${P[props.name] || ""}</svg>`,
);
const px = computed(() => `${props.size}rpx`);

/**
 * 有方向的图标在从右到左（阿语）时水平翻转：「›」在 RTL 里指向左才是「往里走」。
 * 用行内样式而不是 CSS 祖先选择器：小程序组件样式隔离，跨组件的 `.pb-rtl .x` 匹配不到。
 */
const DIRECTIONAL = new Set(["chevron"]);
const app = useAppStore();
const flip = computed(() => (app.isRtl && DIRECTIONAL.has(props.name) ? "transform:scaleX(-1);" : ""));

/**
 * 小程序：同一段 SVG 换成显式颜色，作为 CSS 背景图（URL 编码的 data URI）。
 * 作为独立图片加载的 SVG **必须带 xmlns** —— 内联进 HTML 时可省，data URI 里省了就整张不画、也不报错
 * （开发者工具对照实验：同一段 SVG，带 xmlns 画得出、不带画不出）。宽高不用百分比，与 viewBox 同值。
 */
const mpStyle = computed(() => {
  const raw = svg.value
    .replace("<svg ", '<svg xmlns="http://www.w3.org/2000/svg" ')
    .replace('width="100%" height="100%"', 'width="24" height="24"')
    .replace('stroke="currentColor"', `stroke="${props.color ?? (theme.isDark ? MP_ICON.inkDark : MP_ICON.ink)}"`);
  const uri = "data:image/svg+xml," + encodeURIComponent(raw);
  return `width:${px.value};height:${px.value};background:url("${uri}") center/contain no-repeat;${flip.value}`;
});
</script>

<template>
  <!-- #ifdef MP -->
  <view class="pb-icon pb-icon--mp" :style="mpStyle" />
  <!-- #endif -->
  <!-- #ifndef MP -->
  <view class="pb-icon" :style="`width:${px};height:${px};${flip}`" v-html="svg" />
  <!-- #endif -->
</template>

<style scoped>
.pb-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  line-height: 0;
  flex-shrink: 0;
}
</style>
