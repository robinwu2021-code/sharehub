<script setup lang="ts">
// 列表行（卡片内）。leading 图标用色块圆角 chip 承托；chevron 为线性图标。无分割线。
defineProps<{ title?: string; value?: string; icon?: string; isLink?: boolean }>();
const emit = defineEmits<{ (e: "click"): void }>();
</script>

<template>
  <view class="pb-cell" hover-class="pb-cell--press" :hover-stay-time="40" @tap="emit('click')">
    <view v-if="icon" class="pb-cell__chip"><pb-icon :name="icon" :size="38" /></view>
    <text class="pb-cell__title">{{ title }}</text>
    <view class="pb-cell__value"><slot>{{ value }}</slot></view>
    <pb-icon v-if="isLink" name="chevron" :size="30" class="pb-cell__arrow" />
  </view>
</template>

<style scoped>
.pb-cell { display: flex; align-items: center; gap: 22rpx; padding: 24rpx 2rpx; border-radius: 20rpx; transition: background 0.15s; }
.pb-cell--press { background: var(--pb-faint); }
.pb-cell__chip {
  width: 64rpx;
  height: 64rpx;
  border-radius: 18rpx;
  background: var(--pb-faint);
  color: var(--pb-ink);
  display: flex;
  align-items: center;
  justify-content: center;
}
.pb-cell__title { font-size: 29rpx; font-weight: 600; color: var(--pb-ink); }
.pb-cell__value { flex: 1; text-align: end; font-size: 28rpx; font-weight: 600; color: var(--pb-ink); }
.pb-cell__arrow { color: var(--pb-sub); }
</style>
