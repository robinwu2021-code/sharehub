<script setup lang="ts">
withDefaults(defineProps<{ text?: string; icon?: string }>(), { icon: "receipt" });
</script>

<template>
  <view class="pb-empty">
    <view class="pb-empty__icon"><pb-icon :name="icon" :size="76" :stroke="1.6" /></view>
    <text class="pb-empty__text">{{ text }}</text>
  </view>
</template>

<style scoped>
.pb-empty { display: flex; flex-direction: column; align-items: center; gap: 18rpx; padding: 110rpx 0; }
.pb-empty__icon { color: var(--pb-sub); opacity: 0.45; }
.pb-empty__text { font-size: 26rpx; color: var(--pb-sub); }
</style>
