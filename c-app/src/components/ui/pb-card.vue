<script setup lang="ts">
// 扁平卡片：白面 + 柔和阴影分层（非线条/灰块），大圆角。tint 可染语义色底。
withDefaults(
  defineProps<{ tint?: "" | "primary" | "success" | "warning" | "danger"; pad?: boolean; flat?: boolean }>(),
  { tint: "", pad: true, flat: false },
);
</script>

<template>
  <view class="pb-card" :class="[tint ? `pb-card--${tint}` : '', pad ? 'is-pad' : '', flat ? 'is-flat' : '']">
    <slot />
  </view>
</template>

<style scoped>
.pb-card {
  background: var(--pb-surface);
  border-radius: 30rpx;
  box-shadow: var(--pb-shadow);
  overflow: hidden;
}
.is-pad { padding: 32rpx; }
.is-flat { box-shadow: none; }
.pb-card--primary { background: var(--pb-primary-tint); box-shadow: none; }
.pb-card--success { background: var(--pb-success-tint); box-shadow: none; }
.pb-card--warning { background: var(--pb-warning-tint); box-shadow: none; }
.pb-card--danger { background: var(--pb-danger-tint); box-shadow: none; }
</style>
