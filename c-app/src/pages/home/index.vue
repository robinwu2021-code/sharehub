<script setup lang="ts">
import { ref, computed } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api, IS_MOCK } from "@/api";
import { useI18n } from "vue-i18n";
import type { NearbyCabinet, Notice, RentOrder, StoreDetail } from "@/types";
import { distance } from "@/shared/format";
import { scanCabinet } from "@/ports/scan";
import { openNavigation } from "@/ports/map";

const { t } = useI18n();
const list = ref<NearbyCabinet[]>([]);
const notice = ref<Notice | null>(null);
const ongoing = ref<RentOrder | null>(null);
const loading = ref(true);
const keyword = ref("");
const view = ref("map"); // map | list
const store = ref<StoreDetail | null>(null);
const showStore = ref(false);

const views = computed(() => [
  { label: t("home.map"), value: "map" },
  { label: t("home.list"), value: "list" },
]);

async function load() {
  loading.value = true;
  try {
    const [cabs, notices, ong] = await Promise.all([
      api.nearbyCabinets({ keyword: keyword.value }),
      api.listNotices(),
      api.ongoingOrder(),
    ]);
    list.value = cabs;
    notice.value = notices[0] ?? null;
    ongoing.value = ong;
  } finally {
    loading.value = false;
  }
}
onShow(load);

async function openStore(c: NearbyCabinet) {
  store.value = await api.storeDetail(c.siteNo);
  showStore.value = true;
}
function borrow(c: { cabinetNo?: string; siteNo?: string }) {
  const cabinetNo = c.cabinetNo ?? list.value.find((x) => x.siteNo === c.siteNo)?.cabinetNo;
  if (cabinetNo) uni.navigateTo({ url: `/pages/order/confirm?cabinetNo=${cabinetNo}` });
}
function goOrder() {
  if (ongoing.value) uni.navigateTo({ url: `/pages/order/detail?orderNo=${ongoing.value.orderNo}` });
}
function goNotice() {
  uni.navigateTo({ url: "/pages/notice/index" });
}
async function onScan() {
  // #ifdef H5
  const nearest = list.value.find((c) => c.availableBorrow > 0);
  if (nearest) {
    borrow(nearest);
    return;
  }
  // #endif
  try {
    const cabinetNo = await scanCabinet();
    uni.navigateTo({ url: `/pages/order/confirm?cabinetNo=${cabinetNo}` });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}
</script>

<template>
  <pb-scaffold tab="home">
    <view class="pb-home">
      <!-- 固定头部 -->
      <view class="pb-home__hd">
        <view class="flex items-center justify-between">
          <text class="pb-h1">{{ $t("home.title") }}</text>
          <pb-tag v-if="IS_MOCK" type="warning">{{ $t("common.mock") }}</pb-tag>
        </view>

        <!-- 公告 -->
        <view v-if="notice" class="mt-[24rpx]"><pb-notice-bar :text="notice.title" @click="goNotice" /></view>

        <!-- 使用中订单常驻 -->
        <view v-if="ongoing" class="mt-[20rpx]"><pb-ongoing-bar :order="ongoing" @click="goOrder" /></view>

        <!-- 搜索 -->
        <view class="mt-[24rpx] flex items-center rounded-full bg-faint px-[30rpx] py-[20rpx]">
          <pb-icon name="search" :size="34" class="text-sub" />
          <input v-model="keyword" class="ms-[16rpx] flex-1 text-[28rpx] text-ink" :placeholder="$t('home.searchPlaceholder')" confirm-type="search" @confirm="load" />
        </view>

        <!-- 附近门店 -->
        <view class="mt-[28rpx] flex items-center justify-between">
          <text class="pb-h2">{{ $t("home.nearby") }}</text>
          <view class="w-[220rpx]"><pb-segmented v-model="view" :options="views" /></view>
        </view>
      </view>

      <!-- 地图（填满剩余高度） -->
      <view v-show="view === 'map'" class="pb-home__fill"><pb-map :points="list" @select="openStore" /></view>

      <!-- 列表（填满剩余高度，可滚动） -->
      <scroll-view v-show="view === 'list'" scroll-y class="pb-home__fill pb-home__list">
        <view class="flex flex-col gap-[24rpx] pb-[220rpx]">
          <view v-for="(c, i) in list" :key="c.cabinetNo" class="pb-rise" :style="{ animationDelay: i * 50 + 'ms' }" @tap="openStore(c)">
            <pb-card>
              <view class="flex items-center">
                <view class="flex-1">
                  <text class="pb-h2">{{ c.siteName }}</text>
                  <view class="mt-[8rpx] flex items-center gap-[8rpx] text-[24rpx] text-sub">
                    <pb-icon name="pin" :size="26" /><text>{{ c.address }}{{ distance(c.distanceM) ? " · " + distance(c.distanceM) : "" }}</text>
                  </view>
                  <view class="mt-[18rpx] flex gap-[12rpx]">
                    <pb-tag :type="c.availableBorrow > 0 ? 'success' : 'neutral'">{{ $t("home.canBorrow") }} {{ c.availableBorrow }}</pb-tag>
                    <pb-tag type="primary">{{ $t("home.canReturn") }} {{ c.availableReturn }}</pb-tag>
                  </view>
                </view>
                <view class="ms-[16rpx] flex flex-col items-end gap-[16rpx]">
                  <view class="flex items-baseline gap-[4rpx]"><pb-amount :value="c.pricePerHour" size="sm" /><text class="text-[22rpx] text-sub">{{ $t("common.perHour") }}</text></view>
                  <pb-button size="sm" :type="c.availableBorrow > 0 ? 'primary' : 'tonal'" :disabled="c.availableBorrow <= 0" @click.stop="borrow(c)">
                    {{ c.availableBorrow > 0 ? $t("home.canBorrow") : $t("home.full") }}
                  </pb-button>
                </view>
              </view>
            </pb-card>
          </view>
        </view>
      </scroll-view>
    </view>

    <view class="pb-fab" hover-class="pb-fab--press" :hover-stay-time="40" @tap="onScan">
      <pb-icon name="zap" :size="36" />
      <text>{{ $t("home.scan") }}</text>
    </view>

    <pb-store-sheet
      v-model:visible="showStore"
      :store="store"
      @borrow="(s: StoreDetail) => borrow({ siteNo: s.siteNo })"
      @navigate="(s: StoreDetail) => openNavigation(list.find((c) => c.siteNo === s.siteNo)!)"
    />
  </pb-scaffold>
</template>

<style scoped>
.pb-home { display: flex; flex-direction: column; height: 100%; }
.pb-home__hd { flex: none; padding: 40rpx 32rpx 0; }
/* min-height:0 打破 flex 子项默认按内容撑高，使填充区可收缩、滚动收敛到内部 */
.pb-home__fill { flex: 1; min-height: 0; padding: 20rpx 32rpx 0; box-sizing: border-box; }
.pb-fab {
  position: fixed;
  left: 50%;
  transform: translateX(-50%);
  bottom: 196rpx;
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 24rpx 58rpx;
  border-radius: 9999px;
  background: var(--pb-primary);
  color: var(--pb-on-primary);
  font-size: 30rpx;
  font-weight: 700;
  box-shadow: 0 16rpx 40rpx var(--pb-primary-tint);
  transition: transform 0.12s ease;
}
.pb-fab--press { transform: translateX(-50%) scale(0.96); }
</style>
