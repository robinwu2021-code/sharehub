<script setup lang="ts">
// 页面骨架：底色 + 可选顶部栏 + 可滚动内容 + 可选底部导航。统一各页结构。
defineProps<{ title?: string; showBack?: boolean; tab?: "home" | "orders" | "me" }>();
import { computed, watch } from "vue";
import { useThemeStore } from "@/stores/theme";
import { useAppStore } from "@/stores/app";
import { NAV_BAR_BG } from "@/design/tokens";

/*
 * 小程序没有 <html>，换肤 / 暗色挂不到 data-skin / data-theme 上 —— 此前小程序只有默认皮肤。
 * 改成挂在每页的根（本组件）上：App.vue 里对应的 .pb-skin-* / .pb-mode-dark 只在小程序编译进去，
 * 变量沿节点树继承进所有子组件（弹层、底栏都在 scaffold 里面）。H5 / App 不产生这些规则，class 无副作用。
 */
// 微信自定义组件默认样式隔离：app.wxss 里的 .pb-skin-* / .pb-mode-dark 匹配不到组件内部的节点
// （page{} 能生效是因为变量从 page 往下继承，不需要匹配）。apply-shared 让全局样式作用进来。
defineOptions({ options: { styleIsolation: "apply-shared" } });
const theme = useThemeStore();
/*
 * 阿语从右到左同理：只有 H5 能改 <html dir>，原生 App 与小程序的逻辑层没有 document ——
 * 此前阿语下文字翻译了、布局却仍是从左到右（模拟器实测）。在本节点设 direction，沿节点树继承。
 */
const appStore = useAppStore();
const themeClass = computed(() => [`pb-skin-${theme.skin}`, `pb-mode-${theme.mode}`, appStore.isRtl ? "pb-rtl" : ""]);

// 状态栏文字随明暗切换（全局 custom 导航，状态栏压在页面上）：暗色下黑字在深底上看不见。H5 没有原生状态栏
// #ifndef H5
watch(
  () => theme.mode,
  (m) => {
    uni.setNavigationBarColor({ frontColor: m === "dark" ? "#ffffff" : "#000000", backgroundColor: "#00000000" });
    // #ifdef APP-PLUS
    paintSystemNavBar(m === "dark");
    // #endif
  },
  { immediate: true },
);
// #endif

// #ifdef APP-PLUS
/**
 * 系统导航栏背后的窗口底色 = 底栏面色，并按明暗切换手势条的深浅。
 * 此前那里露出窗口默认的黑色：顶部是页面的白、底部一条黑，屏幕上下的圆角看起来就不一样（真机实测）。
 * 边到边之后 setNavigationBarColor 不再生效，所以改的是 decorView 的底色；失败只是颜色不对，不影响使用。
 */
function paintSystemNavBar(dark: boolean) {
  try {
    const p = (globalThis as unknown as { plus?: any }).plus;
    if (!p || p.os?.name !== "Android") return;
    const activity = p.android.runtimeMainActivity();
    const Color = p.android.importClass("android.graphics.Color");
    const color = Color.parseColor(dark ? NAV_BAR_BG.dark : NAV_BAR_BG.light);
    const win = p.android.invoke(activity, "getWindow");
    const decor = p.android.invoke(win, "getDecorView");
    p.android.invoke(decor, "setBackgroundColor", color);
    p.android.invoke(win, "setNavigationBarColor", color);   // 边到边之前的系统版本仍认这一句
    // 手势条深浅：SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR = 0x10（浅底配深色手势条）
    const flags = p.android.invoke(decor, "getSystemUiVisibility");
    p.android.invoke(decor, "setSystemUiVisibility", dark ? flags & ~0x10 : flags | 0x10);
  } catch (e) {
    console.warn("[pb-scaffold] 系统导航栏着色失败", e);
  }
}
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
    <scroll-view scroll-y class="pb-scaffold__body">
      <slot />
      <!--
        给底部菜单让位用**占位块**而不是滚动区的 padding-bottom：滚动容器的下内边距在部分 WebView 里
        不计入可滚动高度，真机上「退出登录」整个压在菜单下面、滚到底也露不出来。
      -->
      <view v-if="tab" class="pb-scaffold__tab-gap" />
    </scroll-view>
    <pb-tabbar v-if="tab" :active="tab" />
  </view>
</template>

<style scoped>
/* color 在这里重新声明：小程序里暗色变量挂在本节点上，而 page 上的 color 按浅色算好之后是以**值**继承下来的 */
/*
 * overflow: hidden + 滚动区 min-height: 0：只让 scroll-view 滚，外层不许滚。
 * 此前内容一变长（订单刷新成结算态），flex 子项按内容撑开、外层跟着能滚，
 * 整页往上挪了一个状态栏的高度 —— 标题压到状态栏上，按钮也不在手指点的位置（真机实测）。
 */
.pb-scaffold {
  display: flex; flex-direction: column; height: 100vh; overflow: hidden; background: var(--pb-bg); color: var(--pb-ink);
  /* 在这里重算一次：它在根上按根的主色算好后以**值**继承，App / 小程序在本节点换了主色（皮肤 class）它也不会跟着变 */
  --pb-primary-tint: color-mix(in srgb, var(--pb-primary) 12%, transparent);
}
.pb-rtl { direction: rtl; }
.pb-scaffold__status { flex-shrink: 0; height: var(--status-bar-height, 0px); }
.pb-scaffold__body { flex: 1; min-height: 0; box-sizing: border-box; }
/* 菜单高度（约 130rpx）+ 手势条安全区 + 余量；constant/env 分开写，同一条里带 constant() 会被新 WebView 整条丢掉 */
.pb-scaffold__tab-gap {
  height: calc(170rpx + constant(safe-area-inset-bottom));
  height: calc(170rpx + env(safe-area-inset-bottom));
}
</style>
