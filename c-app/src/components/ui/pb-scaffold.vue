<script setup lang="ts">
// 页面骨架：底色 + 可选顶部栏 + 可滚动内容 + 可选底部导航。统一各页结构。
defineProps<{ title?: string; showBack?: boolean; tab?: "home" | "orders" | "me" }>();
</script>

<template>
  <view class="pb-scaffold">
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
.pb-scaffold__body { flex: 1; box-sizing: border-box; }
.pb-scaffold__body.has-tab { padding-bottom: 150rpx; }
</style>
