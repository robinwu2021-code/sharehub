<script setup lang="ts">
/*
 * 关于：版本号此前写死 "1.0.0"，而后端一直有 `GET /mp/app/version`
 * （读 sys_app_version，按平台 + 灰度挑最高构建号）。写死的后果不是显示不准，
 * 是**强制更新这条通道整个用不上** —— 后端标了 forceUpdate 也没人来问。
 */
import { onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useAppStore } from "@/stores/app";
import type { AppVersionCheck } from "@/types";

const { t } = useI18n();
const app = useAppStore();
const info = ref<AppVersionCheck | null>(null);

/** 本地版本号。灰度与最低支持版本由服务端判定，端上只负责报平台。 */
const LOCAL_VERSION = "1.0.0";

// 条件编译拿不到统一的平台名，这里按 uni 的 UNI_PLATFORM 归一
function platform(): string {
  // #ifdef MP-WEIXIN
  return "mp-weixin";
  // #endif
  // #ifdef H5
  return "h5";
  // #endif
  // #ifndef MP-WEIXIN || H5
  return "app";
  // #endif
}

onMounted(async () => {
  try {
    info.value = await api.checkVersion(platform(), app.lang);
  } catch {
    info.value = null; // 查不到就只显示本地版本号，不要卡在「检查中」
  }
});

function goPolicy() {
  uni.navigateTo({ url: "/pages/me/policy" });
}

function update() {
  const url = info.value?.downloadUrl;
  if (!url) return;
  // #ifdef H5
  window.location.href = url;
  // #endif
  // #ifndef H5
  uni.setClipboardData({ data: url, success: () => uni.showToast({ title: t("common.done"), icon: "none" }) });
  // #endif
}
</script>

<template>
  <pb-scaffold :title="$t('settings.about')" show-back>
    <view class="flex flex-col items-center px-[32rpx] pt-[64rpx]">
      <view class="pb-logo"><pb-icon name="zap" :size="90" :stroke="2.4" /></view>
      <text class="mt-[24rpx] text-[42rpx] font-extrabold text-ink">{{ $t("appName") }}</text>
      <text class="mt-[8rpx] text-[24rpx] text-sub">{{ $t("settings.version") }} {{ LOCAL_VERSION }}</text>

      <!-- 有新版本才出这一块；没有就明确说「已是最新」，别留空白让人猜 -->
      <view v-if="info && info.hasUpdate" class="mt-[32rpx] w-full">
        <pb-card tint="primary">
          <text class="text-[28rpx] font-bold text-ink">{{ $t("notice.update", { v: info.versionNo }) }}</text>
          <text v-if="info.releaseNote" class="mt-[10rpx] block text-[25rpx] text-ink" style="line-height: 1.7">
            {{ info.releaseNote }}
          </text>
          <view class="mt-[24rpx]">
            <pb-button block :disabled="!info.downloadUrl" @click="update">{{ $t("notice.updateNow") }}</pb-button>
          </view>
        </pb-card>
      </view>
      <text v-else-if="info" class="mt-[16rpx] text-[22rpx] text-sub">{{ $t("notice.latest") }}</text>

      <view class="mt-[52rpx] w-full">
        <pb-card :pad="false">
          <view class="px-[28rpx]">
            <pb-cell icon="shield" :title="$t('settings.privacy')" is-link @click="goPolicy" />
          </view>
        </pb-card>
      </view>
    </view>
  </pb-scaffold>
</template>

<style scoped>
.pb-logo { width: 168rpx; height: 168rpx; border-radius: 46rpx; background: var(--pb-primary); color: var(--pb-on-primary); display: flex; align-items: center; justify-content: center; box-shadow: 0 20rpx 50rpx var(--pb-primary-tint); }
</style>
