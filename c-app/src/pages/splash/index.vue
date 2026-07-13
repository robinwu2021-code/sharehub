<script setup lang="ts">
import { onLoad } from "@dcloudio/uni-app";
onLoad(() => {
  setTimeout(() => uni.reLaunch({ url: "/pages/home/index" }), 1100);
});
</script>

<template>
  <view class="pb-splash">
    <view class="pb-splash__logo pb-rise"><pb-icon name="zap" :size="120" :stroke="2.4" /></view>
    <text class="pb-splash__name">{{ $t("appName") }}</text>
    <text class="pb-splash__tag">MENA · Global</text>
  </view>
</template>

<style scoped>
.pb-splash { height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 26rpx; background: var(--pb-bg); }
.pb-splash__logo { width: 184rpx; height: 184rpx; border-radius: 52rpx; background: var(--pb-primary); color: var(--pb-on-primary); display: flex; align-items: center; justify-content: center; box-shadow: 0 24rpx 56rpx var(--pb-primary-tint); }
.pb-splash__name { font-size: 54rpx; font-weight: 800; color: var(--pb-ink); letter-spacing: -0.5rpx; }
.pb-splash__tag { font-size: 24rpx; color: var(--pb-sub); letter-spacing: 3rpx; }
</style>
