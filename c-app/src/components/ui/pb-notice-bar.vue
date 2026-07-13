<script setup lang="ts">
// 首页公告条（色块底 + 铃铛图标）。
defineProps<{ text?: string }>();
const emit = defineEmits<{ (e: "click"): void }>();
</script>

<template>
  <view class="pb-notice" hover-class="pb-notice--press" :hover-stay-time="40" @tap="emit('click')">
    <pb-icon name="bell" :size="30" class="pb-notice__ic" />
    <text class="pb-notice__txt">{{ text }}</text>
    <pb-icon name="chevron" :size="26" class="pb-notice__arrow" />
  </view>
</template>

<style scoped>
.pb-notice { display: flex; align-items: center; gap: 14rpx; background: var(--pb-warning-tint); border-radius: 22rpx; padding: 20rpx 24rpx; transition: opacity 0.15s; }
.pb-notice--press { opacity: 0.8; }
.pb-notice__ic { color: var(--pb-warning); }
.pb-notice__txt { flex: 1; font-size: 24rpx; font-weight: 600; color: var(--pb-ink); overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.pb-notice__arrow { color: var(--pb-warning); }
</style>
