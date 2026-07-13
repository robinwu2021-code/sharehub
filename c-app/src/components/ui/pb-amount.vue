<script setup lang="ts">
// 金额展示：币种(小、次色) + 数值(大、主字、等宽数字)。币种随 i18n，与数值有呼吸间距。
import { computed } from "vue";
import { useI18n } from "vue-i18n";
const props = withDefaults(defineProps<{ value: number; size?: "sm" | "md" | "lg" }>(), { size: "md" });
const { t } = useI18n();
const cur = computed(() => t("common.currency"));
</script>

<template>
  <text class="pb-amount pb-num" :class="`is-${props.size}`">
    <text class="pb-amount__cur">{{ cur }}</text>
    <text class="pb-amount__val">{{ props.value.toFixed(2) }}</text>
  </text>
</template>

<style scoped>
.pb-amount { color: var(--pb-ink); font-weight: 800; white-space: nowrap; }
.pb-amount__cur { font-weight: 700; font-size: 0.58em; color: var(--pb-sub); margin-inline-end: 0.35em; letter-spacing: 0.2rpx; }
.pb-amount__val { letter-spacing: -0.3rpx; }
.is-sm { font-size: 30rpx; }
.is-md { font-size: 40rpx; }
.is-lg { font-size: 60rpx; }
</style>
