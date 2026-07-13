<script setup lang="ts">
// 地图占位（stylized）。真实 Google Maps 待 VITE_GMAPS_KEY 后用 renderjs 加载 Maps JavaScript API 替换本组件内部。
import type { NearbyCabinet } from "@/types";
defineProps<{ points: NearbyCabinet[] }>();
const emit = defineEmits<{ (e: "select", p: NearbyCabinet): void }>();
const POS = [
  { left: "22%", top: "30%" },
  { left: "64%", top: "24%" },
  { left: "42%", top: "60%" },
  { left: "78%", top: "56%" },
  { left: "30%", top: "46%" },
];
</script>

<template>
  <view class="pb-map">
    <view class="pb-map__grid" />
    <view class="pb-map__me" />
    <view
      v-for="(p, i) in points.slice(0, 5)"
      :key="p.cabinetNo"
      class="pb-map__pin"
      :class="{ 'is-off': p.availableBorrow <= 0 }"
      :style="POS[i]"
      @tap="emit('select', p)"
    >
      <pb-icon name="pin" :size="46" />
      <text class="pb-map__cnt">{{ p.availableBorrow }}</text>
    </view>
    <view class="pb-map__note">
      <pb-icon name="map" :size="26" />
      <text>Google Maps · VITE_GMAPS_KEY</text>
    </view>
  </view>
</template>

<style scoped>
.pb-map { position: relative; width: 100%; height: 420rpx; border-radius: 28rpx; overflow: hidden; background: var(--pb-faint); box-shadow: var(--pb-shadow); }
.pb-map__grid {
  position: absolute;
  inset: 0;
  background-image: linear-gradient(var(--pb-line, rgba(0, 0, 0, 0.05)) 1px, transparent 1px),
    linear-gradient(90deg, var(--pb-line, rgba(0, 0, 0, 0.05)) 1px, transparent 1px);
  background-size: 56rpx 56rpx;
  opacity: 0.5;
}
.pb-map__me { position: absolute; left: 50%; top: 50%; width: 26rpx; height: 26rpx; margin: -13rpx; border-radius: 9999px; background: var(--pb-primary); box-shadow: 0 0 0 10rpx var(--pb-primary-tint); }
.pb-map__pin { position: absolute; transform: translate(-50%, -100%); color: var(--pb-primary); display: flex; flex-direction: column; align-items: center; }
.pb-map__pin.is-off { color: var(--pb-sub); }
.pb-map__cnt { font-size: 20rpx; font-weight: 800; margin-top: -6rpx; }
.pb-map__note { position: absolute; right: 16rpx; bottom: 16rpx; display: flex; align-items: center; gap: 8rpx; background: var(--pb-surface); border-radius: 9999px; padding: 8rpx 16rpx; font-size: 20rpx; color: var(--pb-sub); box-shadow: var(--pb-shadow-sm); }
</style>
