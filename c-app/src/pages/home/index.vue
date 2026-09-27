<script setup lang="ts">
import { ref, computed } from "vue";
import { onShow, onLoad, onUnload } from "@dcloudio/uni-app";
import { api, IS_MOCK } from "@/api";
import { useI18n } from "vue-i18n";
import type { NearbyCabinet, Notice, ConsumerOrder, StoreDetail } from "@/types";
import { distance } from "@/shared/format";
import { scanCabinet, canScanHere } from "@/ports/scan";

/**
 * 本端能不能扫码。**扫码是借出的主要方式**，所以主按钮恒为扫码，
 * 只有这里为 false（浏览器没有原生解码器 / 没相机 / 非安全上下文，如 Safari）
 * 才退而把手输放到主位 —— 按钮不会「点了没反应」。
 */
const canScan = canScanHere();
/** H5 用自绘取景层；App / 小程序用 uni.scanCode。 */
const useWebScanner = (() => {
  // #ifdef H5
  return true;
  // #endif
  // eslint-disable-next-line no-unreachable
  return false;
})();
const showScanner = ref(false);
import { openNavigation } from "@/ports/map";
import { getUserLocation, onUserLocation } from "@/ports/location";
import { ensureLoggedIn } from "@/shared/auth-gate";
import { useUserStore } from "@/stores/user";
import { useAppStore } from "@/stores/app";
import { noticeTitle } from "@/shared/notice";
import { MP_ICON } from "@/design/tokens";

const { t } = useI18n();
const appStore = useAppStore();
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

/**
 * 搜索键：**以事件里的值为准**，不读 v-model。
 *
 * 安卓输入法（Gboard）打拉丁字母时文字处于「组字」状态，Vue 的 v-model 在组字期间不更新；
 * 用户点键盘上的搜索键时，confirm 比组字结束的那次更新先到 —— 读 keyword 拿到的是半截
 * （模拟器实测：输入 Yas，请求里是 keyword=Y）。e.detail.value 是输入框此刻的完整值。
 */
function onSearch(e: { detail?: { value?: string } }) {
  if (typeof e?.detail?.value === "string") keyword.value = e.detail.value;
  load();
}

/** 每次 load 的序号：只认最后一次发起的结果（见下）。 */
let loadSeq = 0;
/** 当前列表是不是在「不知道位置」时取的 —— 是的话，位置一到就重取一次（带上距离与排序）。 */
let listWithoutLoc = false;

async function load() {
  const seq = ++loadSeq;
  loading.value = true;
  try {
    /*
     * 公告与进行中订单**不等定位**，先发。此前三者都排在 `await getUserLocation()` 后面：
     * 定位慢（App 端十几秒）或挂住时，首页整页空白 —— 连与位置无关的两块都出不来。
     */
    const side = Promise.all([
      api.listNotices(),
      // 游客没有进行中订单可言，也不该去调 —— 那个口要登录，游客调了吃 401，
      // 会话守卫再把人送去登录页：「打开 App 就被要求登录」（Android 模拟器实测撞到）。
      useUserStore().isLoggedIn ? api.ongoingOrder() : Promise.resolve(null),
    ]).then(([notices, ong]) => {
      if (seq !== loadSeq) return;
      notice.value = notices[0] ?? null;
      ongoing.value = ong;
    });

    // 网点列表要定位：后端按 lat/lng 算距离并排序，不给就只能按建站顺序给第一屏。
    // 取不到（含超时）返回 null —— 那时列表不带距离，而不是带一个编出来的距离。
    const at = await getUserLocation();
    const cabs = await api.nearbyCabinets({
      keyword: keyword.value,
      lat: at?.lat,
      lng: at?.lng,
      ...(returnable.value ? { returnable: true } : {}),
    });
    /*
     * **只认最后一次**。连着搜两次（先「Emirates」再「Yas」）时，先发的请求可能后回来，
     * 把新结果盖掉 —— 模拟器上见过：搜索框写着 Yas，列表是 Emirates 的店。
     */
    if (seq === loadSeq) {
      list.value = cabs;
      listWithoutLoc = !at;
    }
    await side;
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
}
onLoad((q) => {
  returnable.value = q?.returnable === "1";
});
onShow(load);
/*
 * 冷启动时定位常常超时（首页先按「不知道位置」出了列表：没有距离、按建站顺序），
 * 坐标几秒后才到。此前要等用户自己切页回来才会带上距离 —— 位置一到就重取一次。
 */
const offLoc = onUserLocation(() => {
  if (listWithoutLoc) void load();
});
onUnload(offLoc);

/**
 * 门店卡上的收藏（心形）。
 *
 * **此前这颗心点了没有任何反应**：pb-store-sheet 发出 favorite 事件，而首页从没监听 ——
 * 登录与否都一样（Android 模拟器实测）。
 * 以切换接口返回的「切换后是否已收藏」为准，不在前端取反 —— 连点两下时取反会和服务端错开。
 */
async function toggleFav(s: StoreDetail) {
  if (!ensureLoggedIn("/pages/home/index")) return;
  try {
    const favorite = await api.toggleFavorite(s.siteNo);
    if (store.value?.siteNo === s.siteNo) store.value = { ...store.value, favorite };
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

async function openStore(c: NearbyCabinet) {
  const at = await getUserLocation();
  store.value = await api.storeDetail(c.siteNo, { lat: at?.lat, lng: at?.lng });
  showStore.value = true;
}
function borrow(c: { cabinetNo?: string; siteNo?: string }) {
  // 从门店卡来的只有 siteNo：挑这家店里**真能借的**那台柜（status=ACTIVE 且有宝）。
  // 此前取的是这家店在列表里的第一台，不管能不能借 —— 模拟器实测点进去的是一台「不可用」的柜。
  const cabinetNo =
    c.cabinetNo ??
    list.value.find((x) => x.siteNo === c.siteNo && x.status !== "PAUSED" && x.availableBorrow > 0)?.cabinetNo;
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
  // 从公告条进来要看的是公告，直接停在「公告」页签
  uni.navigateTo({ url: "/pages/notice/index?tab=notices" });
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

function openManual() {
  manualNo.value = "";
  showManual.value = true;
}

/** 主按钮：优先扫码；浏览器扫不了才落到手输。 */
async function onPrimary() {
  if (!canScan) {
    openManual();
    return;
  }
  if (useWebScanner) {
    showScanner.value = true;
    return;
  }
  try {
    goConfirm(await scanCabinet());
  } catch (e) {
    // 用户主动取消不该弹错误提示；其余失败（没授权/相机被占用）回落到手输
    if (/cancel/i.test((e as Error).message || "")) return;
    openManual();
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
        <view v-else-if="notice" class="mt-[24rpx]"><pb-notice-bar :text="noticeTitle(notice, appStore.lang)" @click="goNotice" /></view>

        <!-- 使用中订单常驻 -->
        <view v-if="ongoing" class="mt-[20rpx]"><pb-ongoing-bar :order="ongoing" @click="goOrder" /></view>

        <!-- 搜索 -->
        <view class="mt-[24rpx] flex items-center rounded-full bg-faint px-[30rpx] py-[20rpx]">
          <pb-icon name="search" :size="34" class="text-sub" />
          <input v-model="keyword" class="ms-[16rpx] flex-1 text-[28rpx] text-ink" :placeholder="$t('home.searchPlaceholder')" confirm-type="search" @confirm="onSearch" />
        </view>

        <!-- 附近门店 -->
        <view class="mt-[28rpx] flex items-center justify-between">
          <text class="pb-h2">{{ $t("home.nearby") }}</text>
          <view class="w-[220rpx]"><pb-segmented v-model="view" :options="views" /></view>
        </view>
      </view>

      <!-- 地图（填满剩余高度） -->
      <!-- 地图底部让出悬浮「扫码 / 输入柜号」那一段：此前地图填到底，谷歌的版权与条款链接被「输入柜号」压住（真机实测），
           而 Google Maps 的使用条款要求它们可见 -->
      <view v-show="view === 'map'" class="pb-home__fill pb-home__map"><pb-map :points="list" @select="openStore" /></view>

      <!-- 列表（填满剩余高度，可滚动） -->
      <scroll-view v-show="view === 'list'" scroll-y class="pb-home__fill pb-home__list">
        <view class="flex flex-col gap-[24rpx] pb-[360rpx]">
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

    <!--
      借出的两个入口：扫码是主，输入编号是辅。
      图标此前是 zap（闪电），读起来是「快充」不是「扫一扫」；文案与能力也要一致。
    -->
    <view class="pb-fab" hover-class="pb-fab--press" :hover-stay-time="40" @tap="onPrimary">
      <pb-icon :name="canScan ? 'scan' : 'keyboard'" :size="36" :color="MP_ICON.onPrimary" />
      <text>{{ canScan ? $t("home.scan") : $t("home.enterCabinet") }}</text>
    </view>

    <!--
      次要入口只在**主按钮是扫码时**出现：那时两种方式并存，扫码为主、手输为辅。
      扫不了码的端（如 Safari）主按钮已经是手输，再挂一个同样的次要入口只是重复。
    -->
    <view v-if="canScan" class="pb-fab-alt" @tap="openManual">
      <pb-icon name="keyboard" :size="26" />
      <text>{{ $t("home.enterCabinet") }}</text>
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

    <!-- H5 取景层：扫到就直接进确认页；层内常驻「输入编号」出口 -->
    <pb-scanner
      v-model:visible="showScanner"
      @result="goConfirm"
      @manual="openManual"
    />

    <pb-store-sheet
      v-model:visible="showStore"
      :store="store"
      @borrow="(s: StoreDetail) => borrow({ siteNo: s.siteNo })"
      @navigate="(s: StoreDetail) => openNavigation(list.find((c) => c.siteNo === s.siteNo)!)"
      @favorite="toggleFav"
    />
  </pb-scaffold>
</template>

<style scoped>
.pb-home { display: flex; flex-direction: column; height: 100%; }
.pb-home__hd { flex: none; padding: 40rpx 32rpx 0; }
/* min-height:0 打破 flex 子项默认按内容撑高，使填充区可收缩、滚动收敛到内部 */
.pb-home__fill { flex: 1; min-height: 0; padding: 20rpx 32rpx 0; box-sizing: border-box; }
/* = 悬浮按钮顶端（230 + 96rpx）减去外壳已给底栏留的 150rpx，再留 24rpx 呼吸 */
.pb-home__map { padding-bottom: 200rpx; }
.pb-fab {
  position: fixed;
  left: 50%;
  transform: translateX(-50%);
  /*
   * 计入安全区：底栏自己加了 env(safe-area-inset-bottom)，会在刘海屏上变高，
   * 而这两个浮层原本写的是固定 rpx —— 在那些机型上会被底栏顶掉。
   */
  bottom: calc(230rpx + constant(safe-area-inset-bottom));
  bottom: calc(230rpx + env(safe-area-inset-bottom));
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12rpx;
  /*
   * 尺寸写死 + 不换行。此前是 padding 撑开，于是宽度随文案长短变，
   * 而文案一长就换行 —— 实测同一个按钮：窗口 1280 时 233×47（一行），
   * 窗口 400 时 200×74（两行）。同一个按钮两种形状。
   * 三种语言 × 两种能力（扫码/手输）一共四句文案，只有固定尺寸才不会各长一个样。
   * 也因此这四句必须短：放不下就会顶出去，而顶出去比换行更难看。
   */
  width: 280rpx;
  height: 96rpx;
  white-space: nowrap;
  border-radius: 9999px;
  background: var(--pb-primary);
  color: var(--pb-on-primary);
  font-size: 30rpx;
  font-weight: 700;
  box-shadow: 0 16rpx 40rpx var(--pb-primary-tint);
  transition: transform 0.12s ease;
}
.pb-fab--press { transform: translateX(-50%) scale(0.96); }
/*
 * 次要入口：压在主按钮与底栏之间那道空档里（实测 400×800 下 FAB 底 695、底栏顶 739，
 * 中间 44px）。刻意做得轻——它是「扫不出来时」的出口，不该跟主按钮抢注意力。
 */
.pb-fab-alt {
  position: fixed;
  left: 50%;
  transform: translateX(-50%);
  bottom: calc(150rpx + constant(safe-area-inset-bottom));
  bottom: calc(150rpx + env(safe-area-inset-bottom));
  display: flex;
  align-items: center;
  gap: 8rpx;
  padding: 8rpx 24rpx;
  border-radius: 9999px;
  color: var(--pb-sub);
  font-size: 24rpx;
  white-space: nowrap;
}
</style>
