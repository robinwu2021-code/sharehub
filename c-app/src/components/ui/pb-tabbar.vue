<script setup lang="ts">
// 自定义底部导航（可换肤）。
//
// 2026-09-28 按用户真机反馈重做选中态：此前是一整格铺满的灰色大块，占满格宽、贴近屏幕圆角，显得笨重。
// 改为 Material 3 的做法 —— 只在**图标后面**放一个小胶囊作指示，图标用主色、文字加粗；未选中一律次要色。
import { computed } from "vue";
import { useThemeStore } from "@/stores/theme";
import { MP_ICON } from "@/design/tokens";

defineProps<{ active: "home" | "orders" | "me" }>();
const tabs = [
  { key: "home", icon: "pin", url: "/pages/home/index", label: "tabbar.home" },
  { key: "orders", icon: "receipt", url: "/pages/orders/index", label: "tabbar.orders" },
  { key: "me", icon: "user", url: "/pages/me/index", label: "tabbar.me" },
] as const;
function go(url: string, key: string, active: string) {
  if (key !== active) uni.reLaunch({ url });
}
// 小程序的图标是背景图、拿不到 currentColor：未选中的颜色显式给（随明暗）；选中项用默认墨色
// （App / H5 忽略 color，仍跟随 currentColor = 主色 / 次要色）
const theme = useThemeStore();
const idleColor = computed(() => (theme.isDark ? MP_ICON.subDark : MP_ICON.sub));
</script>

<template>
  <view class="pb-tabbar">
    <view
      v-for="t in tabs"
      :key="t.key"
      class="pb-tabbar__item"
      :class="{ 'is-active': t.key === active }"
      hover-class="pb-tabbar__item--press"
      :hover-stay-time="60"
      @tap="go(t.url, t.key, active)"
    >
      <view class="pb-tabbar__pill">
        <pb-icon
          :name="t.icon"
          :size="44"
          :stroke="t.key === active ? 2.4 : 2"
          :color="t.key === active ? undefined : idleColor"
        />
      </view>
      <text class="pb-tabbar__label">{{ $t(t.label) }}</text>
    </view>
  </view>
</template>

<style scoped>
.pb-tabbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  /*
   * 左右内边距**单独写**：此前是 `padding: 10rpx 24rpx calc(… constant(…))` 一行简写，
   * 新版 WebView 不认 constant()，整条简写被丢 —— 左右边距没了，选中块贴着屏幕边、被圆角切到（真机实测）。
   * 左右与底部都留足，文字离屏幕两侧的弧度远一点（用户真机反馈）。
   */
  padding: 8rpx 40rpx;
  padding-bottom: calc(16rpx + constant(safe-area-inset-bottom));
  padding-bottom: calc(16rpx + env(safe-area-inset-bottom));
  background: var(--pb-surface);
  box-shadow: 0 -6rpx 28rpx rgba(18, 20, 34, 0.06);
}
.pb-tabbar__item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6rpx;
  padding-top: 8rpx;
  color: var(--pb-sub);
}
/* 选中指示只包住图标：64×32dp 的胶囊（M3 active indicator） */
.pb-tabbar__pill {
  width: 112rpx;
  height: 60rpx;
  border-radius: 9999px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s ease, transform 0.12s ease;
}
.pb-tabbar__label { font-size: 22rpx; font-weight: 600; letter-spacing: 0.2rpx; line-height: 1.2; }
.pb-tabbar__item.is-active { color: var(--pb-primary); }
.pb-tabbar__item.is-active .pb-tabbar__pill { background: var(--pb-primary-tint); }
.pb-tabbar__item.is-active .pb-tabbar__label { color: var(--pb-ink); font-weight: 700; }
.pb-tabbar__item--press .pb-tabbar__pill { transform: scale(0.92); }
</style>
