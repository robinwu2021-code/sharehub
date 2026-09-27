<script setup lang="ts">
import { onLaunch } from "@dcloudio/uni-app";
import { useAppStore } from "@/stores/app";
import { useThemeStore } from "@/stores/theme";
import { useUserStore } from "@/stores/user";
import { initPush } from "@/ports/push";

onLaunch(() => {
  useThemeStore().init(); // 皮肤 + 明暗
  useAppStore().initLocale(); // 语言 + RTL
  useUserStore().installSessionGuard(); // 401 → 清会话并回登录页
  initPush();
});
</script>

<style>
/* ===== 主题变量：皮肤(色) × 明暗(风格)。换肤=改 <html> 上 data-skin/data-theme，全局即时生效 ===== */
/*
 * ⚠️ 必须同时写 page：小程序的 WXSS 不认 :root，只写 :root 时所有 var(--pb-*) 在小程序里都取不到值 ——
 * 背景全透明、按钮不是黑底、公告条没底色（微信开发者工具实测）。App / H5 跑在 WebView 里，一直没暴露。
 * 换肤 / 暗色靠 <html> 上的 data 属性，小程序没有那个节点，目前只有默认皮肤。
 * page 只能在小程序里出现：H5 / App 会把它编译成页面主体节点（<html> 的后代），
 * 在那里再定义一遍浅色变量会盖掉 <html> 上的暗色 / 换肤 —— 所以用条件编译。
 * ⚠️ 也**不能写成 `page, :root {`**：小程序的样式引擎会把含 :root 的整条选择器列表丢掉
 * （开发者工具对照：`page,:root{}` 全部失色，改成 `page{}` 恢复）。每端只给自己那一个选择器。
 */
/* #ifdef MP */
page {
/* #endif */
/* #ifndef MP */
:root {
/* #endif */
  /* 默认皮肤：黑白灰（mono），白底为基 */
  --pb-primary: #18181b;
  --pb-on-primary: #ffffff;
  --pb-success: #12b76a;
  --pb-warning: #f79009;
  --pb-danger: #f04438;

  /* 浅色中性面：纯白底 + 白卡（靠柔和阴影分层，非灰块/线条） */
  --pb-bg: #ffffff;
  --pb-surface: #ffffff;
  --pb-elev: #ffffff;
  --pb-ink: #16171d;
  --pb-sub: #8a8d97;
  --pb-faint: #f2f3f6;
  --pb-shadow: 0 6rpx 28rpx rgba(18, 20, 34, 0.06);
  --pb-shadow-sm: 0 2rpx 10rpx rgba(18, 20, 34, 0.05);

  /* 色块底：由主色/语义色派生（明暗通用），扁平设计的核心，替代线条 */
  --pb-primary-tint: color-mix(in srgb, var(--pb-primary) 12%, transparent);
  --pb-success-tint: color-mix(in srgb, var(--pb-success) 16%, transparent);
  --pb-warning-tint: color-mix(in srgb, var(--pb-warning) 16%, transparent);
  --pb-danger-tint: color-mix(in srgb, var(--pb-danger) 16%, transparent);
}

/* 皮肤：只改主色（中性面由明暗决定）。mono=黑白灰 / blue=时尚蓝 / purple=科幻紫 */
/*
 * 换肤 / 暗色：只有 H5 能改 <html> 的属性。小程序没有 <html>；原生 App 的业务 JS 跑在**逻辑层**，没有 document ——
 * theme store 里的 setAttribute 在 App 端静默失败，原生包的换肤与暗色**从来没生效过**（模拟器实测：选暗色只有系统栏变了）。
 * 所以 H5 以外都靠 pb-scaffold 根节点上的 class（见其 themeClass）；变量沿节点树继承进组件。
 */
/* #ifndef H5 */
.pb-skin-blue {
/* #endif */
/* #ifdef H5 */
:root[data-skin="blue"] {
/* #endif */
  --pb-primary: #2f6bff;
}
/* #ifndef H5 */
.pb-skin-purple {
/* #endif */
/* #ifdef H5 */
:root[data-skin="purple"] {
/* #endif */
  --pb-primary: #7c3aed;
}

/* 深色风格：改中性面 */
/* #ifndef H5 */
.pb-mode-dark {
/* #endif */
/* #ifdef H5 */
:root[data-theme="dark"] {
/* #endif */
  --pb-bg: #0b0e14;
  --pb-surface: #161a22;
  --pb-elev: #1e232d;
  --pb-ink: #f2f4f7;
  --pb-sub: #97a0af;
  --pb-faint: #232937;
  --pb-shadow: 0 6rpx 28rpx rgba(0, 0, 0, 0.32);
  --pb-shadow-sm: 0 2rpx 10rpx rgba(0, 0, 0, 0.28);
}
/*
 * 默认皮肤（黑白灰）+ 暗色：主色要换成浅色。此前暗色块不动主色，而默认主色是近黑 #18181b ——
 * 深底上选中的 Tab、主按钮文字都看不见（模拟器实测，H5 同样如此）。
 * 只对默认皮肤：蓝 / 紫的主色在深底上本来就看得清。两个 class（两个属性）的优先级高于单个皮肤规则，与书写顺序无关。
 */
/* #ifndef H5 */
.pb-skin-mono.pb-mode-dark {
/* #endif */
/* #ifdef H5 */
:root[data-skin="mono"][data-theme="dark"] {
/* #endif */
  --pb-primary: #f2f4f7;
  --pb-on-primary: #0b0e14;
}

/* ===== 扁平基座 ===== */
page {
  background-color: var(--pb-bg);
  color: var(--pb-ink);
  /* 软润字体：拉丁 Nunito / 阿语 Tajawal / 中文 Noto Sans SC（按字形自动回退），系统兜底 */
  font-family: "Nunito", "Tajawal", "Noto Sans SC", -apple-system, "Segoe UI", Roboto, sans-serif;
  font-size: 28rpx;
  line-height: 1.5;
  letter-spacing: 0.1rpx;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  text-rendering: optimizeLegibility;
  transition: background-color 0.25s ease, color 0.25s ease;
}
/* 标题微紧字距（Nunito 大字更利落）；金额用等宽数字 */
.pb-h1 { font-size: 46rpx; font-weight: 800; letter-spacing: -0.4rpx; line-height: 1.2; }
.pb-h2 { font-size: 34rpx; font-weight: 700; letter-spacing: -0.2rpx; }
.pb-num { font-variant-numeric: tabular-nums; font-feature-settings: "tnum" 1; }
/* 列表入场：淡入 + 轻微上移（配合行内错峰 animation-delay） */
@keyframes pbRise { from { opacity: 0; transform: translateY(18rpx); } to { opacity: 1; transform: none; } }
.pb-rise { animation: pbRise 0.4s cubic-bezier(0.22, 1, 0.36, 1) both; }

/* #ifdef H5 */
/* ===== H5：限制最大宽度并居中 =====
 *
 * 这套 UI 的尺寸全部以 rpx 写就（基准宽 750），本质是**按手机宽度设计**的。
 * 在桌面浏览器里 H5 会一直撑满窗口，于是窗口越宽，卡片越扁、正文行越长、
 * 底栏的四个图标被拉到屏幕两端 —— 不是「宽屏适配」，是变形。
 *
 * ⚠️ 关键在 transform 那一行，不是 max-width：
 * 自绘底栏、首页 FAB、各种弹层都是 position: fixed，**相对视口定位**，
 * 祖先的 max-width 管不到它们 —— 只加 max-width 的话，内容居中了而底栏
 * 仍然横贯整个桌面窗口。给 uni-app 加 transform 让它成为 fixed 后代的
 * **包含块**，那些元素就自动收进这一列，不必逐个组件改定位。
 * （translateZ(0) 不产生视觉位移，只为建立包含块。）
 */
:root {
  /* ⚠️ 与 manifest.json 的 h5.rpxCalcMaxDeviceWidth / rpxCalcBaseDeviceWidth 是同一个数：
     应用列有多宽，rpx 就按多宽折算。改这里必须同时改那里，否则列宽与内容基准不同步，
     结果就是「列不动、内容一直长」（那正是加这两项之前的样子）。 */
  --pb-appw: 480px;
  /* 列外侧的页面底色：比应用本身略深一点，宽屏上才分得清「应用」与「背景」 */
  --pb-outside: #eceef2;
}
:root[data-theme="dark"] {
  --pb-outside: #0b0c10;
}

html {
  background: var(--pb-outside);
  /*
   * ===== 锁住 rpx 的基准，界面不再随窗口变形 =====
   *
   * uni 在 H5 下把 rpx **编译成 rem**（280rpx → 8.75rem，即 32rpx = 1rem），
   * 再用 JS 给 <html> 内联一个 font-size = 窗口宽 / 750 * 32。
   * 于是窗口越宽，字、间距、按钮一起变大 —— 而应用列已经被 --pb-appw 限住不动了。
   * 实测窗口 800：主按钮 299×102（占 480 列宽的 62%）、.pb-h2 36px；
   * 同一个按钮窗口 400 时是 149×51。这就是「不同尺寸下形状不同」的根。
   *
   * 这里把基准**封顶在应用列宽**：窗口超过 --pb-appw 之后 font-size 不再增长，
   * 整套界面固定成「一台 480 宽的手机」；480 以下仍按窗口缩放（手机本该如此）。
   * 一处生效，字体 / 间距 / 圆角 / 按钮全都跟着锁住，不用逐个组件改。
   *
   * ⚠️ 必须 !important：那个 font-size 是 uni 用 JS 写在 <html> 上的**内联样式**，
   * 样式表里不带 !important 压不过它。
   *
   * ⚠️ manifest.json 的 h5.rpxCalcMaxDeviceWidth 一类选项在本版 uni 上**不生效**
   * （实测 __uniConfig 里没有对应键、uni.upx2px(280) 仍按窗口算），别再去那边试。
   */
  font-size: min(calc(100vw / 750 * 32), calc(var(--pb-appw) / 750 * 32)) !important;
}

uni-app {
  max-width: var(--pb-appw);
  margin: 0 auto;
  min-height: 100vh;
  background: var(--pb-bg);
  /* 见上：为 fixed 后代建立包含块 */
  transform: translateZ(0);
  /* 宽屏上给一道极淡的边界，窄屏（宽度未超上限）时看不出来 */
  box-shadow: 0 0 40rpx rgba(18, 20, 34, 0.08);
}
/* #endif */
</style>
