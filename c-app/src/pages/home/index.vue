<script setup lang="ts">
import { ref, computed } from "vue";
import { onShow, onLoad } from "@dcloudio/uni-app";
import { api, IS_MOCK } from "@/api";
import { useI18n } from "vue-i18n";
import type { NearbyCabinet, Notice, ConsumerOrder, StoreDetail } from "@/types";
import { distance } from "@/shared/format";
import { scanCabinet } from "@/ports/scan";

/** 本端有没有扫码能力。H5（含 Capacitor 的纯 WebView）没有 uni.scanCode。 */
const canScan = (() => {
  // #ifdef H5
  return false;
  // #endif
  // eslint-disable-next-line no-unreachable
  return true;
})();
import { openNavigation } from "@/ports/map";
import { getUserLocation } from "@/ports/location";
import { ensureLoggedIn } from "@/shared/auth-gate";

const { t } = useI18n();
const list = ref<NearbyCabinet[]>([]);
const notice = ref<Notice | null>(null);
const ongoing = ref<ConsumerOrder | null>(null);
const loading = ref(true);
const keyword = ref("");
const view = ref("map"); // map | list
const store = ref<StoreDetail | null>(null);
const showStore = ref(false);
/**
 * 「找可还网点」模式（由使用中订单详情带 `?returnable=1` 进来，C-US-03）。
 *
 * 归还是任意点归还，难的不是知道「可以随便还」，而是**手上这颗宝现在能还到哪** ——
 * 附近柜子满仓的时候，站在柜子前才发现插不进去。所以这一屏只留有空仓的柜。
 */
const returnable = ref(false);

const views = computed(() => [
  { label: t("home.map"), value: "map" },
  { label: t("home.list"), value: "list" },
]);

async function load() {
  loading.value = true;
  try {
    // 定位先取：后端按 lat/lng 算距离并排序，不给就只能按建站顺序给第一屏。
    // 取不到返回 null —— 那时列表不带距离，而不是带一个编出来的距离。
    const at = await getUserLocation();
    const [cabs, notices, ong] = await Promise.all([
      api.nearbyCabinets({
        keyword: keyword.value,
        lat: at?.lat,
        lng: at?.lng,
        ...(returnable.value ? { returnable: true } : {}),
      }),
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
onLoad((q) => {
  returnable.value = q?.returnable === "1";
});
onShow(load);

async function openStore(c: NearbyCabinet) {
  const at = await getUserLocation();
  store.value = await api.storeDetail(c.siteNo, { lat: at?.lat, lng: at?.lng });
  showStore.value = true;
}
function borrow(c: { cabinetNo?: string; siteNo?: string }) {
  const cabinetNo = c.cabinetNo ?? list.value.find((x) => x.siteNo === c.siteNo)?.cabinetNo;
  if (!cabinetNo) return;
  const url = `/pages/order/confirm?cabinetNo=${cabinetNo}`;
  // 门禁前置：未登录先登录，登录完直接回到这一页（带着柜机号）。
  // 让他点到提交再吃 401 的话，会话守卫会 reLaunch 回登录页，这次借出的上下文全丢。
  if (ensureLoggedIn(url)) uni.navigateTo({ url });
}
function goOrder() {
  if (ongoing.value) uni.navigateTo({ url: `/pages/order/detail?orderNo=${ongoing.value.orderNo}` });
}
function goNotice() {
  uni.navigateTo({ url: "/pages/notice/index" });
}
/**
 * 主按钮：能扫码就扫码，不能就让用户**照着柜机上的编号输**。
 *
 * ⚠️ 这里原先在 H5 下的做法是：取列表里第一台有货的柜机，直接进确认页。
 * 按钮写着「扫码借出」，而它既没扫码、也没问过用户 —— 用户可能就这样
 * 从一家他根本没选过的店借走一台宝。**展示在说谎**，而且是替用户做了决定。
 *
 * uni.scanCode 是 App / 小程序的能力，H5 没有（当前 Android 产物是 Capacitor 壳，
 * 纯 WebView 里同样没有）。所以按平台分：有扫码能力就扫，没有就输编号 ——
 * 柜机上印着编号，这是扫码失败时的标准兜底，也是唯一不替用户猜的做法。
 */
const showManual = ref(false);
const manualNo = ref("");

function goConfirm(cabinetNo: string) {
  const url = `/pages/order/confirm?cabinetNo=${cabinetNo.trim().toUpperCase()}`;
  if (ensureLoggedIn(url)) uni.navigateTo({ url });
}

async function onScan() {
  if (!canScan) {
    manualNo.value = "";
    showManual.value = true;
    return;
  }
  try {
    goConfirm(await scanCabinet());
  } catch (e) {
    // 用户主动取消不该弹错误提示；其余失败给一句「扫不出来就手输」的出口
    const msg = (e as Error).message || "";
    if (/cancel/i.test(msg)) return;
    manualNo.value = "";
    showManual.value = true;
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

        <!-- 找可还网点模式：只列有空仓的柜，并说明为什么这一屏变少了 -->
        <view v-if="returnable" class="mt-[24rpx]"><pb-notice-bar :text="$t('home.returnableOnly')" /></view>

        <!-- 公告 -->
        <view v-else-if="notice" class="mt-[24rpx]"><pb-notice-bar :text="notice.title" @click="goNotice" /></view>

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
                    <!--
                      status=PAUSED 是「这台机柜此刻给不出宝」（离线/故障），与「满仓」不是一回事：
                      满仓换个柜子还就行，停用则这家店白来。此前端上不读 status，
                      两种情况都渲染成「满仓」。
                    -->
                    <pb-tag v-if="c.status === 'PAUSED'" type="neutral">{{ $t("home.paused") }}</pb-tag>
                    <pb-tag v-else :type="c.availableBorrow > 0 ? 'success' : 'neutral'">{{ $t("home.canBorrow") }} {{ c.availableBorrow }}</pb-tag>
                    <pb-tag type="primary">{{ $t("home.canReturn") }} {{ c.availableReturn }}</pb-tag>
                  </view>
                </view>
                <view class="ms-[16rpx] flex flex-col items-end gap-[16rpx]">
                  <view class="flex items-baseline gap-[4rpx]"><pb-amount :value="c.pricePerHour" size="sm" /><text class="text-[22rpx] text-sub">{{ $t("common.perHour") }}</text></view>
                  <pb-button size="sm" :type="c.availableBorrow > 0 ? 'primary' : 'tonal'" :disabled="c.availableBorrow <= 0" @click.stop="borrow(c)">
                    {{ c.status === "PAUSED" ? $t("home.paused") : c.availableBorrow > 0 ? $t("home.canBorrow") : $t("home.full") }}
                  </pb-button>
                </view>
              </view>
            </pb-card>
          </view>
        </view>
      </scroll-view>
    </view>

    <!-- 图标此前是 zap（闪电），读起来是「快充」不是「扫一扫」；文案与能力也要一致 -->
    <view class="pb-fab" hover-class="pb-fab--press" :hover-stay-time="40" @tap="onScan">
      <pb-icon :name="canScan ? 'scan' : 'keyboard'" :size="36" />
      <text>{{ canScan ? $t("home.scan") : $t("home.enterCabinet") }}</text>
    </view>

    <!-- 无扫码能力时的兜底：照着柜机上印的编号输 -->
    <pb-dialog
      v-model:visible="showManual"
      v-model:inputValue="manualNo"
      :title="$t('home.enterCabinet')"
      :message="$t('home.enterCabinetHint')"
      :input-placeholder="$t('home.cabinetNoPlaceholder')"
      @confirm="goConfirm(manualNo)"
    />

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
