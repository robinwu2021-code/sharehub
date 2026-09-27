<script setup lang="ts">
// 页面骨架：底色 + 可选顶部栏 + 可滚动内容 + 可选底部导航。统一各页结构。
defineProps<{ title?: string; showBack?: boolean; tab?: "home" | "orders" | "me" }>();
import { computed, watch } from "vue";
import { useThemeStore } from "@/stores/theme";

/*
 * 小程序没有 <html>，换肤 / 暗色挂不到 data-skin / data-theme 上 —— 此前小程序只有默认皮肤。
 * 改成挂在每页的根（本组件）上：App.vue 里对应的 .pb-skin-* / .pb-mode-dark 只在小程序编译进去，
 * 变量沿节点树继承进所有子组件（弹层、底栏都在 scaffold 里面）。H5 / App 不产生这些规则，class 无副作用。
 */
// 微信自定义组件默认样式隔离：app.wxss 里的 .pb-skin-* / .pb-mode-dark 匹配不到组件内部的节点
// （page{} 能生效是因为变量从 page 往下继承，不需要匹配）。apply-shared 让全局样式作用进来。
defineOptions({ options: { styleIsolation: "apply-shared" } });
const theme = useThemeStore();
const themeClass = computed(() => [`pb-skin-${theme.skin}`, `pb-mode-${theme.mode}`]);

// 状态栏文字随明暗切换（全局 custom 导航，状态栏压在页面上）：暗色下黑字在深底上看不见。H5 没有原生状态栏
// #ifndef H5
watch(
  () => theme.mode,
  (m) => uni.setNavigationBarColor({ frontColor: m === "dark" ? "#ffffff" : "#000000", backgroundColor: "#00000000" }),
  { immediate: true },
);
// #endif
</script>

<template>
  <view class="pb-scaffold" :class="themeClass">
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
/* color 在这里重新声明：小程序里暗色变量挂在本节点上，而 page 上的 color 按浅色算好之后是以**值**继承下来的 */
.pb-scaffold { display: flex; flex-direction: column; height: 100vh; background: var(--pb-bg); color: var(--pb-ink); }
.pb-scaffold__status { flex-shrink: 0; height: var(--status-bar-height, 0px); }
.pb-scaffold__body { flex: 1; box-sizing: border-box; }
.pb-scaffold__body.has-tab { padding-bottom: 150rpx; }
</style>
