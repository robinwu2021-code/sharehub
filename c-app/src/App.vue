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
:root {
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
:root[data-skin="blue"] {
  --pb-primary: #2f6bff;
}
:root[data-skin="purple"] {
  --pb-primary: #7c3aed;
}

/* 深色风格：改中性面 */
:root[data-theme="dark"] {
  --pb-bg: #0b0e14;
  --pb-surface: #161a22;
  --pb-elev: #1e232d;
  --pb-ink: #f2f4f7;
  --pb-sub: #97a0af;
  --pb-faint: #232937;
  --pb-shadow: 0 6rpx 28rpx rgba(0, 0, 0, 0.32);
  --pb-shadow-sm: 0 2rpx 10rpx rgba(0, 0, 0, 0.28);
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
  --pb-appw: 480px;
  /* 列外侧的页面底色：比应用本身略深一点，宽屏上才分得清「应用」与「背景」 */
  --pb-outside: #eceef2;
}
:root[data-theme="dark"] {
  --pb-outside: #0b0c10;
}

html {
  background: var(--pb-outside);
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
