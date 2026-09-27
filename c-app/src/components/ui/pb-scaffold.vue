<script setup lang="ts">
// 页面骨架：底色 + 可选顶部栏 + 可滚动内容 + 可选底部导航。统一各页结构。
defineProps<{ title?: string; showBack?: boolean; tab?: "home" | "orders" | "me" }>();
</script>

<template>
  <view class="pb-scaffold">
    <!--
      状态栏占位。全局 navigationStyle: custom，页面从屏幕最顶上开始画 —— 此前没有这一行，
      标题 / 返回箭头压在状态栏的时间和图标上（Android 14 模拟器实测，刘海机的状态栏更高、压得更多）。
      --status-bar-height 由 uni 注入：App / 小程序是原生状态栏高度，H5 为 0。
    -->
    <view class="pb-scaffold__status" />
    <pb-appbar v-if="title || showBack" :title="title" :show-back="showBack">
      <template #right><slot name="appbar-right" /></template>
    </pb-appbar>
    <scroll-view scroll-y class="pb-scaffold__body" :class="{ 'has-tab': tab }">
      <slot />
    </scroll-view>
    <pb-tabbar v-if="tab" :active="tab" />
  </view>
</template>

<style scoped>
.pb-scaffold { display: flex; flex-direction: column; height: 100vh; background: var(--pb-bg); }
.pb-scaffold__status { flex-shrink: 0; height: var(--status-bar-height, 0px); }
.pb-scaffold__body { flex: 1; box-sizing: border-box; }
.pb-scaffold__body.has-tab { padding-bottom: 150rpx; }
</style>
