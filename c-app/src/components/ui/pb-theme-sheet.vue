<script setup lang="ts">
// 外观选择器（底部弹层）：皮肤(色) + 明暗(风格) + 语言(中/英/阿)。选中即时全局生效（实时预览）。
import { useThemeStore } from "@/stores/theme";
import { useAppStore } from "@/stores/app";
import { SKINS, MODES } from "@/design/tokens";
import { LANGS } from "@/shared/constants";

defineProps<{ visible: boolean }>();
const emit = defineEmits<{ (e: "update:visible", v: boolean): void }>();
const theme = useThemeStore();
const app = useAppStore();
function close() {
  emit("update:visible", false);
}
</script>

<template>
  <view v-if="visible" class="pb-sheet">
    <view class="pb-sheet__mask" @tap="close" />
    <view class="pb-sheet__panel">
      <view class="pb-sheet__grip" />
      <text class="pb-sheet__title">{{ $t("theme.title") }}</text>

      <text class="pb-sheet__label">{{ $t("theme.skin") }}</text>
      <view class="pb-sheet__swatches">
        <view
          v-for="s in SKINS"
          :key="s.id"
          class="pb-swatch"
          :class="{ 'is-on': theme.skin === s.id }"
          @tap="theme.setSkin(s.id)"
        >
          <view class="pb-swatch__dot" :style="{ background: s.color }">
            <text v-if="theme.skin === s.id" class="pb-swatch__tick">✓</text>
          </view>
          <text class="pb-swatch__name">{{ s.label[app.lang] }}</text>
        </view>
      </view>

      <text class="pb-sheet__label">{{ $t("theme.mode") }}</text>
      <view class="pb-opts">
        <view
          v-for="m in MODES"
          :key="m.id"
          class="pb-opts__item"
          :class="{ 'is-on': theme.mode === m.id }"
          @tap="theme.setMode(m.id)"
        >
          {{ m.label[app.lang] }}
        </view>
      </view>

      <text class="pb-sheet__label">{{ $t("theme.language") }}</text>
      <view class="pb-opts">
        <view
          v-for="l in LANGS"
          :key="l.id"
          class="pb-opts__item"
          :class="{ 'is-on': app.lang === l.id }"
          @tap="app.setLang(l.id)"
        >
          {{ l.label }}
        </view>
      </view>

      <view class="pb-sheet__done" @tap="close">{{ $t("common.done") }}</view>
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
  padding: 20rpx 32rpx calc(40rpx + env(safe-area-inset-bottom));
}
.pb-sheet__grip { width: 72rpx; height: 8rpx; border-radius: 9999px; background: var(--pb-faint); margin: 0 auto 20rpx; }
.pb-sheet__title { display: block; text-align: center; font-size: 32rpx; font-weight: 700; color: var(--pb-ink); margin-bottom: 8rpx; }
.pb-sheet__label { display: block; font-size: 24rpx; color: var(--pb-sub); margin: 28rpx 0 16rpx; }
.pb-sheet__swatches { display: flex; gap: 20rpx; }
.pb-swatch { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 12rpx; }
.pb-swatch__dot { width: 88rpx; height: 88rpx; border-radius: 9999px; display: flex; align-items: center; justify-content: center; }
.pb-swatch.is-on .pb-swatch__dot { box-shadow: 0 0 0 6rpx var(--pb-surface), 0 0 0 12rpx var(--pb-primary); }
.pb-swatch__tick { color: #fff; font-size: 40rpx; font-weight: 700; }
.pb-swatch__name { font-size: 24rpx; color: var(--pb-ink); }
.pb-opts { display: flex; gap: 16rpx; }
.pb-opts__item {
  flex: 1;
  text-align: center;
  padding: 22rpx 0;
  border-radius: 20rpx;
  background: var(--pb-faint);
  color: var(--pb-sub);
  font-size: 26rpx;
}
.pb-opts__item.is-on { background: var(--pb-primary-tint); color: var(--pb-primary); font-weight: 600; }
.pb-sheet__done {
  margin-top: 36rpx;
  text-align: center;
  padding: 26rpx 0;
  border-radius: 9999px;
  background: var(--pb-primary);
  color: var(--pb-on-primary);
  font-size: 30rpx;
  font-weight: 600;
}
</style>
