<script setup lang="ts">
// 自定义底部导航（可换肤）。线性图标 + 激活项主色 tint 药丸。顶部柔和阴影分层。
defineProps<{ active: "home" | "orders" | "me" }>();
const tabs = [
  { key: "home", icon: "pin", url: "/pages/home/index", label: "tabbar.home" },
  { key: "orders", icon: "receipt", url: "/pages/orders/index", label: "tabbar.orders" },
  { key: "me", icon: "user", url: "/pages/me/index", label: "tabbar.me" },
] as const;
function go(url: string, key: string, active: string) {
  if (key !== active) uni.reLaunch({ url });
}
</script>

<template>
  <view class="pb-tabbar">
    <view
      v-for="t in tabs"
      :key="t.key"
      class="pb-tabbar__item"
      :class="{ 'is-active': t.key === active }"
      @tap="go(t.url, t.key, active)"
    >
      <pb-icon :name="t.icon" :size="42" :stroke="t.key === active ? 2.4 : 2" />
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
  gap: 12rpx;
  padding: 10rpx 24rpx calc(10rpx + constant(safe-area-inset-bottom));
  padding-bottom: calc(10rpx + env(safe-area-inset-bottom));
  background: var(--pb-surface);
  box-shadow: 0 -6rpx 28rpx rgba(18, 20, 34, 0.05);
}
.pb-tabbar__item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6rpx;
  padding: 12rpx 0;
  border-radius: 22rpx;
  color: var(--pb-sub);
  transition: color 0.2s, background 0.2s;
}
.pb-tabbar__item.is-active { color: var(--pb-primary); background: var(--pb-primary-tint); }
.pb-tabbar__label { font-size: 21rpx; font-weight: 600; letter-spacing: 0.2rpx; }
</style>
