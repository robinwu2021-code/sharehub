<script setup lang="ts">
// 区号选择底部弹层（全球版）。国旗 + 国家 + 区号，支持搜索。
import { ref, computed } from "vue";
import { COUNTRIES } from "@/shared/countries";
import type { Country } from "@/types";

defineProps<{ visible: boolean }>();
const emit = defineEmits<{ (e: "update:visible", v: boolean): void; (e: "select", c: Country): void }>();

const kw = ref("");
const list = computed(() =>
  COUNTRIES.filter(
    (c) => !kw.value || c.name.toLowerCase().includes(kw.value.toLowerCase()) || c.dial.includes(kw.value),
  ),
);
function pick(c: Country) {
  emit("select", c);
  emit("update:visible", false);
}
</script>

<template>
  <view v-if="visible" class="pb-sheet">
    <view class="pb-sheet__mask" @tap="emit('update:visible', false)" />
    <view class="pb-sheet__panel">
      <view class="pb-sheet__grip" />
      <view class="pb-cc__search">
        <pb-icon name="search" :size="30" class="text-sub" />
        <input v-model="kw" class="ms-[12rpx] flex-1 text-[28rpx] text-ink" placeholder="Search" />
      </view>
      <scroll-view scroll-y class="pb-cc__list">
        <view v-for="c in list" :key="c.iso" class="pb-cc__item" @tap="pick(c)">
          <text class="pb-cc__flag">{{ c.flag }}</text>
          <text class="pb-cc__name">{{ c.name }}</text>
          <text class="pb-cc__dial">{{ c.dial }}</text>
        </view>
      </scroll-view>
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
  height: 72vh;
  background: var(--pb-surface);
  border-radius: 32rpx 32rpx 0 0;
  padding: 20rpx 32rpx calc(20rpx + env(safe-area-inset-bottom));
  display: flex;
  flex-direction: column;
}
.pb-sheet__grip { width: 72rpx; height: 8rpx; border-radius: 9999px; background: var(--pb-faint); margin: 0 auto 20rpx; }
.pb-cc__search { display: flex; align-items: center; background: var(--pb-faint); border-radius: 9999px; padding: 16rpx 26rpx; }
.pb-cc__list { flex: 1; margin-top: 16rpx; }
.pb-cc__item { display: flex; align-items: center; gap: 20rpx; padding: 24rpx 8rpx; }
.pb-cc__flag { font-size: 40rpx; }
.pb-cc__name { flex: 1; font-size: 28rpx; color: var(--pb-ink); }
.pb-cc__dial { font-size: 28rpx; font-weight: 700; color: var(--pb-sub); }
</style>
