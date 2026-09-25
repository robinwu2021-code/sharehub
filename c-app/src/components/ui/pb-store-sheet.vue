<script setup lang="ts">
// 门店详情底部弹层（地图点选后）。地址/营业/可借可还/价格/导航/借出/收藏。
import type { StoreDetail } from "@/types";
import { distance } from "@/shared/format";
defineProps<{ visible: boolean; store: StoreDetail | null }>();
const emit = defineEmits<{
  (e: "update:visible", v: boolean): void;
  (e: "borrow", s: StoreDetail): void;
  (e: "navigate", s: StoreDetail): void;
  (e: "favorite", s: StoreDetail): void;
}>();
function close() {
  emit("update:visible", false);
}
</script>

<template>
  <view v-if="visible && store" class="pb-sheet">
    <view class="pb-sheet__mask" @tap="close" />
    <view class="pb-sheet__panel">
      <view class="pb-sheet__grip" />
      <view class="flex items-start justify-between">
        <text class="pb-h2 flex-1">{{ store.siteName }}</text>
        <view class="pb-fav" :class="{ 'is-on': store.favorite }" @tap="emit('favorite', store)">
          <pb-icon name="heart" :size="34" />
        </view>
      </view>
      <view class="mt-[12rpx] flex items-center gap-[10rpx] text-[24rpx] text-sub">
        <pb-icon name="pin" :size="26" /><text>{{ store.address }}{{ distance(store.distanceM) ? " · " + distance(store.distanceM) : "" }}</text>
      </view>
      <view class="mt-[8rpx] flex items-center gap-[10rpx] text-[24rpx] text-sub">
        <pb-icon name="clock" :size="26" /><text>{{ store.openHours }}</text>
      </view>

      <view class="mt-[24rpx] flex gap-[16rpx]">
        <view class="pb-kpi">
          <text class="pb-kpi__n">{{ store.availableBorrow }}</text>
          <text class="pb-kpi__l">{{ $t("home.canBorrow") }}</text>
        </view>
        <view class="pb-kpi">
          <text class="pb-kpi__n">{{ store.availableReturn }}</text>
          <text class="pb-kpi__l">{{ $t("home.canReturn") }}</text>
        </view>
        <view class="pb-kpi">
          <text class="pb-kpi__n pb-num">{{ store.currency }} {{ store.pricePerHour }}</text>
          <text class="pb-kpi__l">{{ $t("common.perHour") }}</text>
        </view>
      </view>

      <view class="mt-[28rpx] flex gap-[16rpx]">
        <pb-button block type="tonal" @click="emit('navigate', store)">{{ $t("common.navigate") }}</pb-button>
        <pb-button block :disabled="store.availableBorrow <= 0" @click="emit('borrow', store)">{{ $t("home.canBorrow") }}</pb-button>
      </view>
    </view>
  </view>
</template>

<style scoped>
.pb-sheet { position: fixed; inset: 0; z-index: 100; }
.pb-sheet__mask { position: absolute; inset: 0; background: rgba(0, 0, 0, 0.4); }
.pb-sheet__panel {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  background: var(--pb-surface);
  border-radius: 32rpx 32rpx 0 0;
  padding: 20rpx 32rpx calc(36rpx + env(safe-area-inset-bottom));
}
.pb-sheet__grip { width: 72rpx; height: 8rpx; border-radius: 9999px; background: var(--pb-faint); margin: 0 auto 20rpx; }
.pb-fav { color: var(--pb-sub); padding: 6rpx; }
.pb-fav.is-on { color: var(--pb-danger); }
.pb-kpi { flex: 1; background: var(--pb-faint); border-radius: 20rpx; padding: 22rpx 8rpx; display: flex; flex-direction: column; align-items: center; gap: 6rpx; }
.pb-kpi__n { font-size: 34rpx; font-weight: 800; color: var(--pb-ink); }
.pb-kpi__l { font-size: 22rpx; color: var(--pb-sub); }
</style>
