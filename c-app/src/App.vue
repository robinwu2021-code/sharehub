<script setup lang="ts">
import { onLaunch } from "@dcloudio/uni-app";
import { useAppStore } from "@/stores/app";
import { useThemeStore } from "@/stores/theme";
import { initPush } from "@/ports/push";

onLaunch(() => {
  useThemeStore().init(); // 皮肤 + 明暗
  useAppStore().initLocale(); // 语言 + RTL
  initPush();
});
</script>

<style>
/* ===== 主题变量：皮肤(色) × 明暗(风格)。换肤=改 <html> 上 data-skin/data-theme，全局即时生效 ===== */
:root {
  /* 默认皮肤：简电青（brand，取自原型 简电/JD Charge 品牌青绿），白底为基 */
  --pb-primary: #17c3c0;
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

/* 皮肤：只改主色（中性面由明暗决定）。brand=简电青 / mono=黑白灰 / blue=时尚蓝 / purple=科幻紫 */
:root[data-skin="brand"] {
  --pb-primary: #17c3c0;
}
:root[data-skin="mono"] {
  --pb-primary: #18181b;
}
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
</style>
