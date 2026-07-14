<script setup lang="ts">
// 附近门店（二级页）：地图/列表切换 + 门店点选。原「首页」地图体验迁移至此，首页改为仪表盘。
import { ref, computed } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api, IS_MOCK } from "@/api";
import { useI18n } from "vue-i18n";
import { useUserStore } from "@/stores/user";
import type { NearbyCabinet, RentOrder, StoreDetail } from "@/types";
import { distance } from "@/shared/format";
import { scanCabinet } from "@/ports/scan";
import { openNavigation } from "@/ports/map";

const { t } = useI18n();
const userStore = useUserStore();
const list = ref<NearbyCabinet[]>([]);
const ongoing = ref<RentOrder | null>(null);
const loading = ref(true);
const scanning = ref(false); // 防止扫码/借出过程中重复触发
const keyword = ref("");
const view = ref("map"); // map | list
const store = ref<StoreDetail | null>(null);
const showStore = ref(false);
const mapRef = ref<{ recenter: () => void } | null>(null);

function recenter() {
  mapRef.value?.recenter();
}

const views = computed(() => [
  { label: t("home.map"), value: "map" },
  { label: t("home.list"), value: "list" },
]);

async function load() {
  loading.value = true;
  try {
    const [cabs, ong] = await Promise.all([
      api.nearbyCabinets({ keyword: keyword.value }),
      api.ongoingOrder(),
    ]);
    list.value = cabs;
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
// 借出前置：未登录先引导登录（借还需身份+支付，符合真实业务）
function ensureLogin(): boolean {
  if (userStore.isLoggedIn) return true;
  uni.showToast({ title: t("home.loginToBorrow"), icon: "none" });
  setTimeout(() => uni.navigateTo({ url: "/pages/login/index" }), 600);
  return false;
}
function borrow(c: { cabinetNo?: string; siteNo?: string }) {
  if (!ensureLogin()) return;
  const cabinetNo = c.cabinetNo ?? list.value.find((x) => x.siteNo === c.siteNo)?.cabinetNo;
  if (cabinetNo) uni.navigateTo({ url: `/pages/order/confirm?cabinetNo=${cabinetNo}` });
}
function goOrder() {
  if (ongoing.value) uni.navigateTo({ url: `/pages/order/detail?orderNo=${ongoing.value.orderNo}` });
}
// 收藏切换：同步更新列表卡片与门店弹层，视图间状态一致
async function toggleFavorite(siteNo: string) {
  const { favorite } = await api.toggleFavorite(siteNo);
  const item = list.value.find((c) => c.siteNo === siteNo);
  if (item) item.favorite = favorite;
  if (store.value?.siteNo === siteNo) store.value.favorite = favorite;
  uni.showToast({ title: t(favorite ? "favorites.added" : "favorites.removed"), icon: "none" });
}
async function onScan() {
  if (scanning.value) return; // 防抖：扫码/借出进行中忽略重复点击
  if (!ensureLogin()) return; // 登录门槛：开摄像头前拦截
  scanning.value = true;
  try {
    // #ifdef H5
    // H5 无扫码硬件：就近取一台可借柜机直达借出确认（开发/预览用）
    const nearest = list.value.find((c) => c.availableBorrow > 0);
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
  <pb-scaffold :title="$t('home.nearby')" show-back>
    <view class="pb-stores" :class="{ 'pb-stores--map': view === 'map' }">
      <!-- 全屏地图层（仅地图视图）：铺满整页，头部悬浮其上 -->
      <view v-show="view === 'map'" class="pb-stores__map">
        <pb-map ref="mapRef" full :points="list" :selected="showStore ? store?.siteNo : null" @select="openStore" />
        <view class="pb-stores__locate" hover-class="pb-stores__locate--press" :hover-stay-time="40" @tap="recenter">
          <pb-icon name="locate" :size="40" />
        </view>
      </view>

      <!-- 头部：列表视图为常规文档流；地图视图为悬浮玻璃层 -->
      <view class="pb-stores__hd" :class="{ 'pb-stores__hd--float': view === 'map' }">
        <view v-if="IS_MOCK" class="flex items-center justify-end">
          <pb-tag type="warning">{{ $t("common.mock") }}</pb-tag>
        </view>

        <!-- 使用中订单常驻 -->
        <view v-if="ongoing" class="mt-[20rpx]"><pb-ongoing-bar :order="ongoing" @click="goOrder" /></view>

        <!-- 搜索 -->
        <view class="pb-stores__search mt-[24rpx] flex items-center rounded-full px-[30rpx] py-[20rpx]">
          <pb-icon name="search" :size="34" class="text-sub" />
          <input v-model="keyword" class="ms-[16rpx] flex-1 text-[28rpx] text-ink" :placeholder="$t('home.searchPlaceholder')" confirm-type="search" @confirm="load" />
        </view>

        <!-- 视图切换 -->
        <view class="mt-[28rpx] flex items-center justify-between">
          <text class="pb-h2">{{ $t("home.nearby") }}</text>
          <view class="w-[220rpx]"><pb-segmented v-model="view" :options="views" /></view>
        </view>
      </view>

      <!-- 列表（填满剩余高度，可滚动） -->
      <scroll-view v-show="view === 'list'" scroll-y class="pb-stores__fill pb-stores__list">
        <view class="flex flex-col gap-[24rpx] pb-[220rpx]">
          <view v-for="(c, i) in list" :key="c.cabinetNo" class="pb-rise" :style="{ animationDelay: i * 50 + 'ms' }" @tap="openStore(c)">
            <pb-card>
              <view class="flex items-center gap-[24rpx]">
                <view class="pb-store-thumb"><pb-icon name="store" :size="54" /></view>
                <view class="flex-1">
                  <view class="flex items-center gap-[10rpx]">
                    <text class="pb-h2">{{ c.siteName }}</text>
                    <view class="pb-fav-mini" :class="{ 'is-on': c.favorite }" hover-class="pb-fav-mini--press" :hover-stay-time="40" @tap.stop="toggleFavorite(c.siteNo)">
                      <pb-icon name="heart" :size="30" />
                    </view>
                  </view>
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
                    {{ c.availableBorrow > 0 ? $t("home.canBorrow") : $t("home.full") }}
                  </pb-button>
                </view>
              </view>
            </pb-card>
          </view>
        </view>
      </scroll-view>
    </view>

    <view class="pb-fab" :class="{ 'pb-fab--busy': scanning }" hover-class="pb-fab--press" :hover-stay-time="40" @tap="onScan">
      <pb-icon name="zap" :size="36" />
      <text>{{ $t("home.scan") }}</text>
    </view>

    <pb-store-sheet
      v-model:visible="showStore"
      :store="store"
      @borrow="(s: StoreDetail) => borrow({ siteNo: s.siteNo })"
      @navigate="(s: StoreDetail) => openNavigation(list.find((c) => c.siteNo === s.siteNo)!)"
      @favorite="(s: StoreDetail) => toggleFavorite(s.siteNo)"
    />
  </pb-scaffold>
</template>

<style scoped>
.pb-stores { display: flex; flex-direction: column; height: 100%; position: relative; }
.pb-stores__hd { flex: none; padding: 20rpx 32rpx 0; }
/* 地图视图：头部悬浮在地图上，底部渐隐蒙版保证搜索可读 */
.pb-stores__hd--float {
  position: absolute;
  top: 0; left: 0; right: 0;
  z-index: 10;
  padding-bottom: 28rpx;
  background: linear-gradient(180deg, var(--pb-bg) 42%, color-mix(in srgb, var(--pb-bg) 72%, transparent) 78%, transparent);
}
/* 全屏地图层：边到边铺满整页 */
.pb-stores__map { position: absolute; inset: 0; }
/* 搜索框：默认浅底；悬浮在地图上时改白面 + 阴影，更清晰 */
.pb-stores__search { background: var(--pb-faint); }
.pb-stores__hd--float .pb-stores__search { background: var(--pb-surface); box-shadow: var(--pb-shadow); }
/* 定位按钮（回到我的位置） */
.pb-stores__locate {
  position: absolute;
  right: 40rpx; bottom: 320rpx;
  width: 84rpx; height: 84rpx;
  border-radius: 9999px;
  background: var(--pb-surface);
  color: var(--pb-primary);
  display: flex; align-items: center; justify-content: center;
  box-shadow: var(--pb-shadow);
  transition: transform 0.12s ease;
}
.pb-stores__locate--press { transform: scale(0.92); }
/* min-height:0 打破 flex 子项默认按内容撑高，使填充区可收缩、滚动收敛到内部 */
.pb-stores__fill { flex: 1; min-height: 0; padding: 20rpx 32rpx 0; box-sizing: border-box; }
.pb-fab {
  position: fixed;
  left: 50%;
  transform: translateX(-50%);
  bottom: 60rpx;
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
.pb-fab--busy { opacity: 0.6; pointer-events: none; }
.pb-fav-mini {
  color: var(--pb-sub);
  padding: 4rpx;
  transition: transform 0.12s ease, color 0.18s ease;
}
.pb-fav-mini.is-on { color: var(--pb-danger); }
.pb-fav-mini--press { transform: scale(0.85); }
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
