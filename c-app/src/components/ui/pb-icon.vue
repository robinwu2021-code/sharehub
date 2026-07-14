<script setup lang="ts">
// 描边线性图标（currentColor 跟随文字色，随皮肤/明暗变化）。替代 emoji，统一精致度。
// H5/App(WebView) 用 v-html 注入 SVG；小程序端 SVG 受限（后续可换 base64/字体图标）。
import { computed } from "vue";

const props = withDefaults(defineProps<{ name: string; size?: number; stroke?: number }>(), {
  size: 40,
  stroke: 2,
});

// Lucide 风格 24x24 路径
const P: Record<string, string> = {
  search: '<circle cx="11" cy="11" r="7.5"/><path d="m21 21-4.3-4.3"/>',
  zap: '<path d="M13 2 4 13.5h7L10 22l9-11.5h-7L13 2Z"/>',
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
  locate: '<circle cx="12" cy="12" r="7"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3"/><circle cx="12" cy="12" r="2.4"/>',
  list: '<path d="M8 6h13M8 12h13M8 18h13"/><path d="M3.5 6h.01M3.5 12h.01M3.5 18h.01"/>',
  home: '<path d="m3 10 9-7 9 7v9.5a1.5 1.5 0 0 1-1.5 1.5H15v-6H9v6H4.5A1.5 1.5 0 0 1 3 19.5Z"/>',
  store: '<path d="M4 9.6V20a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1V9.6"/><path d="M3 3h18l1.2 4.2a2.4 2.4 0 0 1-4.7.8 2.4 2.4 0 0 1-4.75 0 2.4 2.4 0 0 1-4.75 0 2.4 2.4 0 0 1-4.7-.8L3 3Z"/><path d="M9.5 21v-4.5h5V21"/>',
};

const svg = computed(
  () =>
    `<svg viewBox="0 0 24 24" width="100%" height="100%" fill="none" stroke="currentColor" stroke-width="${props.stroke}" stroke-linecap="round" stroke-linejoin="round">${P[props.name] || ""}</svg>`,
);
const px = computed(() => `${props.size}rpx`);
</script>

<template>
  <view class="pb-icon" :style="{ width: px, height: px }" v-html="svg" />
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
