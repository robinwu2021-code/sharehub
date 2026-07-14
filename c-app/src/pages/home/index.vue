<script setup lang="ts">
// 首页（仪表盘型）：品牌 hero + 公告 + 快捷入口九宫格 + 常用网点。地图体验已迁移至 /pages/stores。
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api, IS_MOCK } from "@/api";
import { useI18n } from "vue-i18n";
import { useUserStore } from "@/stores/user";
import type { NearbyCabinet, Notice, RentOrder } from "@/types";
import { distance } from "@/shared/format";
import { scanCabinet } from "@/ports/scan";

const { t } = useI18n();
const userStore = useUserStore();
const notice = ref<Notice | null>(null);
const ongoing = ref<RentOrder | null>(null);
const frequent = ref<NearbyCabinet[]>([]);
const scanning = ref(false); // 防止扫码/借出过程中重复触发

async function load() {
  const [notices, ong, favs, nearby] = await Promise.all([
    api.listNotices(),
    api.ongoingOrder(),
    userStore.isLoggedIn ? api.listFavorites() : Promise.resolve([] as NearbyCabinet[]),
    api.nearbyCabinets(),
  ]);
  notice.value = notices[0] ?? null;
  ongoing.value = ong;
  // 常用网点：优先收藏门店；未登录/无收藏则回退到附近门店
  frequent.value = (favs.length ? favs : nearby).slice(0, 3);
}
onShow(load);

// 借出前置：未登录先引导登录（借还需身份+支付，符合真实业务）
function ensureLogin(): boolean {
  if (userStore.isLoggedIn) return true;
  uni.showToast({ title: t("home.loginToBorrow"), icon: "none" });
  setTimeout(() => uni.navigateTo({ url: "/pages/login/index" }), 600);
  return false;
}
function borrow(c: NearbyCabinet) {
  if (!ensureLogin()) return;
  if (c.cabinetNo) uni.navigateTo({ url: `/pages/order/confirm?cabinetNo=${c.cabinetNo}` });
}
function goStores() {
  uni.navigateTo({ url: "/pages/stores/index" });
}
function goOrders() {
  uni.reLaunch({ url: "/pages/orders/index" });
}
function goOrder() {
  if (ongoing.value) uni.navigateTo({ url: `/pages/order/detail?orderNo=${ongoing.value.orderNo}` });
}
function goNotice() {
  uni.navigateTo({ url: "/pages/notice/index" });
}
async function onScan() {
  if (scanning.value) return; // 防抖：扫码/借出进行中忽略重复点击
  if (!ensureLogin()) return; // 登录门槛：开摄像头前拦截
  scanning.value = true;
  try {
    // #ifdef H5
    // H5 无扫码硬件：就近取一台可借柜机直达借出确认（开发/预览用）
    const nearby = await api.nearbyCabinets();
    const nearest = nearby.find((c) => c.availableBorrow > 0);
    if (!nearest) {
      uni.showToast({ title: t("home.noBorrowable"), icon: "none" });
      return;
    }
    borrow(nearest);
    return;
    // #endif
    const cabinetNo = await scanCabinet();
    uni.navigateTo({ url: `/pages/order/confirm?cabinetNo=${cabinetNo}` });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    scanning.value = false;
  }
}
</script>

<template>
  <pb-scaffold tab="home">
    <view class="px-[32rpx] pt-[40rpx]">
      <!-- 品牌 hero + 扫码 CTA -->
      <view class="pb-hero">
        <view class="pb-hero__glow" />
        <view class="flex items-center justify-between">
          <text class="pb-hero__brand">{{ $t("home.brand") }}</text>
          <pb-tag v-if="IS_MOCK" type="warning">{{ $t("common.mock") }}</pb-tag>
        </view>
        <text class="pb-hero__tagline">{{ $t("home.brandTagline") }}</text>
        <view class="pb-hero__scan" :class="{ 'is-busy': scanning }" hover-class="pb-hero__scan--press" :hover-stay-time="40" @tap="onScan">
          <pb-icon name="zap" :size="38" />
          <text>{{ $t("home.scan") }}</text>
        </view>
      </view>

      <!-- 公告 -->
      <view v-if="notice" class="mt-[24rpx]"><pb-notice-bar :text="notice.title" @click="goNotice" /></view>

      <!-- 快捷入口：左「使用中」高卡 + 右两格（订单 / 附近门店） -->
      <view class="mt-[24rpx] flex gap-[20rpx]">
        <view class="pb-entry pb-entry--tall" hover-class="pb-entry--press" :hover-stay-time="40" @tap="ongoing ? goOrder() : onScan()">
          <view>
            <text class="pb-entry__title">{{ $t("home.ongoingTitle") }}</text>
            <view class="pb-entry__sub">{{ ongoing ? $t("home.ongoingWait", { count: 1 }) : $t("home.noOngoing") }}</view>
          </view>
          <view class="pb-entry__icon"><pb-icon name="battery" :size="76" :stroke="1.6" /></view>
        </view>

        <view class="flex flex-1 flex-col gap-[20rpx]">
          <view class="pb-entry pb-entry--half" hover-class="pb-entry--press" :hover-stay-time="40" @tap="goOrders">
            <view>
              <text class="pb-entry__title">{{ $t("home.myOrders") }}</text>
              <view class="pb-entry__sub">{{ $t("home.myOrdersSub") }}</view>
            </view>
            <view class="pb-entry__mini text-primary"><pb-icon name="receipt" :size="44" /></view>
          </view>
          <view class="pb-entry pb-entry--half" hover-class="pb-entry--press" :hover-stay-time="40" @tap="goStores">
            <view>
              <text class="pb-entry__title">{{ $t("home.nearby") }}</text>
              <view class="pb-entry__sub">{{ $t("home.nearbySub") }}</view>
            </view>
            <view class="pb-entry__mini text-primary"><pb-icon name="pin" :size="44" /></view>
          </view>
        </view>
      </view>

      <!-- 常用网点 -->
      <view class="mt-[36rpx] flex items-center justify-between">
        <text class="pb-h2">{{ $t("home.frequent") }}</text>
        <text class="text-[24rpx] text-sub" @tap="goStores">{{ $t("home.viewAll") }} ›</text>
      </view>

      <view class="mt-[20rpx] flex flex-col gap-[24rpx] pb-[40rpx]">
        <view v-for="(c, i) in frequent" :key="c.cabinetNo" class="pb-rise" :style="{ animationDelay: i * 50 + 'ms' }" @tap="goStores">
          <pb-card>
            <view class="flex items-center gap-[24rpx]">
              <view class="pb-store-thumb"><pb-icon name="store" :size="54" /></view>
              <view class="flex-1">
                <text class="pb-h2">{{ c.siteName }}</text>
                <view class="mt-[8rpx] flex items-center gap-[8rpx] text-[24rpx] text-sub">
                  <pb-icon name="pin" :size="26" /><text>{{ c.address }} · {{ distance(c.distanceM) }}</text>
                </view>
                <view class="mt-[18rpx] flex gap-[12rpx]">
                  <pb-tag :type="c.availableBorrow > 0 ? 'success' : 'neutral'">{{ $t("home.canBorrow") }} {{ c.availableBorrow }}</pb-tag>
                  <pb-tag type="primary">{{ $t("home.canReturn") }} {{ c.availableReturn }}</pb-tag>
                </view>
              </view>
              <view class="ms-[16rpx] flex flex-col items-end gap-[16rpx]">
                <view class="flex items-baseline gap-[4rpx]"><pb-amount :value="c.pricePerHour" size="sm" /><text class="text-[22rpx] text-sub">{{ $t("common.perHour") }}</text></view>
                <pb-button size="sm" :type="c.availableBorrow > 0 ? 'primary' : 'tonal'" :disabled="c.availableBorrow <= 0" @click.stop="borrow(c)">
                  {{ c.availableBorrow > 0 ? $t("home.goBorrow") : $t("home.full") }}
                </pb-button>
              </view>
            </view>
          </pb-card>
        </view>
        <pb-empty v-if="!frequent.length" :text="$t('home.noBorrowable')" />
      </view>
    </view>
  </pb-scaffold>
</template>

<style scoped>
/* 品牌 hero：青绿渐变 + 柔光斑 + 扫码 CTA */
.pb-hero {
  position: relative;
  overflow: hidden;
  padding: 40rpx 36rpx;
  border-radius: 32rpx;
  color: var(--pb-on-primary);
  background: linear-gradient(135deg, var(--pb-primary), color-mix(in srgb, var(--pb-primary) 72%, #000 14%));
  box-shadow: 0 16rpx 40rpx var(--pb-primary-tint);
}
.pb-hero__glow {
  position: absolute;
  top: -80rpx; right: -60rpx;
  width: 280rpx; height: 280rpx;
  border-radius: 9999px;
  background: rgba(255, 255, 255, 0.16);
}
.pb-hero__brand { font-size: 44rpx; font-weight: 800; letter-spacing: 1rpx; }
.pb-hero__tagline { margin-top: 12rpx; display: block; font-size: 26rpx; opacity: 0.9; }
.pb-hero__scan {
  margin-top: 36rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14rpx;
  padding: 24rpx;
  border-radius: 9999px;
  background: var(--pb-surface);
  color: var(--pb-primary);
  font-size: 32rpx;
  font-weight: 700;
  transition: transform 0.12s ease;
}
.pb-hero__scan--press { transform: scale(0.97); }
.pb-hero__scan.is-busy { opacity: 0.6; pointer-events: none; }
/* 快捷入口卡 */
.pb-entry {
  position: relative;
  overflow: hidden;
  display: flex;
  border-radius: 26rpx;
  background: var(--pb-surface);
  box-shadow: var(--pb-shadow);
  padding: 28rpx;
  transition: transform 0.12s ease;
}
.pb-entry--press { transform: scale(0.97); }
.pb-entry--tall { flex: 1; flex-direction: column; justify-content: space-between; min-height: 260rpx; }
.pb-entry--half { align-items: center; justify-content: space-between; }
.pb-entry__title { font-size: 30rpx; font-weight: 700; color: var(--pb-ink); }
.pb-entry__sub { margin-top: 6rpx; font-size: 22rpx; color: var(--pb-sub); }
.pb-entry__icon { align-self: flex-end; color: var(--pb-primary); opacity: 0.9; }
.pb-entry__mini { flex: none; }
/* 门店缩略图占位：品牌浅底 + 门店图标 */
.pb-store-thumb {
  flex: none;
  width: 112rpx;
  height: 112rpx;
  border-radius: 22rpx;
  background: var(--pb-primary-tint);
  color: var(--pb-primary);
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
