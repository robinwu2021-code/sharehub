<script setup lang="ts">
function go() {
  uni.navigateTo({ url: "/pages/me/policy" });
}
</script>

<template>
  <pb-scaffold :title="$t('settings.about')" show-back>
    <view class="flex flex-col items-center px-[32rpx] pt-[64rpx]">
      <view class="pb-logo"><pb-icon name="zap" :size="90" :stroke="2.4" /></view>
      <text class="mt-[24rpx] text-[42rpx] font-extrabold text-ink">{{ $t("appName") }}</text>
      <text class="mt-[8rpx] text-[24rpx] text-sub">{{ $t("settings.version") }} 1.0.0</text>
      <view class="mt-[52rpx] w-full">
        <pb-card :pad="false">
          <view class="px-[28rpx]"><pb-cell icon="shield" :title="$t('settings.privacy')" is-link @click="go" /></view>
        </pb-card>
      </view>
    </view>
  </pb-scaffold>
</template>

<style scoped>
.pb-logo { width: 168rpx; height: 168rpx; border-radius: 46rpx; background: var(--pb-primary); color: var(--pb-on-primary); display: flex; align-items: center; justify-content: center; box-shadow: 0 20rpx 50rpx var(--pb-primary-tint); }
</style>
