<script setup lang="ts">
// 色块标签：tint 底 + 同色字，无边框。
withDefaults(defineProps<{ type?: "primary" | "success" | "warning" | "danger" | "neutral" }>(), {
  type: "neutral",
});
</script>

<template>
  <text class="pb-tag" :class="`pb-tag--${type}`"><slot /></text>
</template>

<style scoped>
.pb-tag { display: inline-block; padding: 5rpx 18rpx; border-radius: 9999px; font-size: 22rpx; line-height: 1.7; font-weight: 700; letter-spacing: 0.2rpx; }
.pb-tag--primary { background: var(--pb-primary-tint); color: var(--pb-primary); }
.pb-tag--success { background: var(--pb-success-tint); color: var(--pb-success); }
.pb-tag--warning { background: var(--pb-warning-tint); color: var(--pb-warning); }
.pb-tag--danger { background: var(--pb-danger-tint); color: var(--pb-danger); }
.pb-tag--neutral { background: var(--pb-faint); color: var(--pb-sub); }
</style>
