<script setup lang="ts">
defineProps<{ label: string }>();
</script>

<template>
  <view class="pb-stat">
    <view class="pb-stat__val"><slot /></view>
    <text class="pb-stat__label">{{ label }}</text>
  </view>
</template>

<style scoped>
.pb-stat { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 8rpx; }
.pb-stat__val { font-size: 40rpx; font-weight: 700; color: var(--pb-ink); }
.pb-stat__label { font-size: 24rpx; color: var(--pb-sub); }
</style>
