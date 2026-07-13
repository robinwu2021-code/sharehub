<script setup lang="ts">
// 顶部栏（navigationStyle:custom 下自绘）。返回键在 RTL 下由 flex 自动镜像到另一侧。
defineProps<{ title?: string; showBack?: boolean }>();
function back() {
  uni.navigateBack({ fail: () => uni.reLaunch({ url: "/pages/home/index" }) });
}
</script>

<template>
  <view class="pb-appbar">
    <view class="pb-appbar__side" @tap="showBack && back()">
      <text v-if="showBack" class="pb-appbar__back">‹</text>
    </view>
    <text class="pb-appbar__title">{{ title }}</text>
    <view class="pb-appbar__side pb-appbar__side--end"><slot name="right" /></view>
  </view>
</template>

<style scoped>
.pb-appbar { display: flex; align-items: center; height: 88rpx; padding: 0 20rpx; background: var(--pb-bg); }
.pb-appbar__side { min-width: 88rpx; display: flex; align-items: center; }
.pb-appbar__side--end { justify-content: flex-end; }
.pb-appbar__back { font-size: 56rpx; color: var(--pb-ink); line-height: 1; }
.pb-appbar__title { flex: 1; text-align: center; font-size: 32rpx; font-weight: 600; color: var(--pb-ink); }
</style>
