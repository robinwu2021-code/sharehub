<script setup lang="ts">
// 空态。**「没有数据」与「没加载出来」是两件事**：后者必须能重试，
// 否则加载失败会被渲染成一句肯定的假话（「你没有订单」），用户以为数据没了。
withDefaults(defineProps<{ text?: string; icon?: string; actionText?: string }>(), { icon: "receipt" });
defineEmits<{ (e: "action"): void }>();
</script>

<template>
  <view class="pb-empty">
    <view class="pb-empty__icon"><pb-icon :name="icon" :size="76" :stroke="1.6" /></view>
    <text class="pb-empty__text">{{ text }}</text>
    <view v-if="actionText" class="pb-empty__action" @tap="$emit('action')">{{ actionText }}</view>
  </view>
</template>

<style scoped>
.pb-empty { display: flex; flex-direction: column; align-items: center; gap: 18rpx; padding: 110rpx 0; }
.pb-empty__icon { color: var(--pb-sub); opacity: 0.45; }
.pb-empty__text { font-size: 26rpx; color: var(--pb-sub); text-align: center; padding: 0 64rpx; line-height: 1.5; }
.pb-empty__action {
  margin-top: 10rpx;
  padding: 16rpx 44rpx;
  border-radius: 9999px;
  background: var(--pb-primary-tint);
  color: var(--pb-primary);
  font-size: 26rpx;
  font-weight: 600;
}
</style>
