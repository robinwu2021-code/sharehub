<script setup lang="ts">
// 借出确认 → 下单 → 等弹出(dispensing) → 借出成功。免押/押金二选一（端侧 PaymentPort，MVP Stub）。
import { computed, ref } from "vue";
import { onLoad, onUnload } from "@dcloudio/uni-app";
import { api } from "@/api";
import { t } from "@/i18n";
import type { CabinetAvailability, UserCoupon } from "@/types";
import { payment } from "@/ports/payment";
import { ensureLoggedIn } from "@/shared/auth-gate";
import { POLL_INTERVAL_MS, DISPENSE_TIMEOUT_MS } from "@/shared/constants";

const avail = ref<CabinetAvailability | null>(null);
const agree = ref(true);
const useFree = ref(true);
const coupons = ref<UserCoupon[]>([]);
const picked = ref<string | null>(null);
const pickerOpen = ref(false);
const stage = ref<"confirm" | "dispensing" | "success" | "failed">("confirm");
const orderNo = ref("");
let cabinetNo = "";
let pollTimer: ReturnType<typeof setInterval> | null = null;

onLoad((q) => {
  cabinetNo = (q?.cabinetNo as string) || "";
  load();
});
async function load() {
  avail.value = await api.cabinetAvailability(cabinetNo);
  // 券包与本页是两个接口，券拉失败不该把整个借出流程堵死 —— 顶多这次借不成用券
  try {
    coupons.value = await api.listCoupons();
  } catch {
    coupons.value = [];
  }
}

/**
 * 这里的筛选条件要和后端 `CouponUsePort.offerOf` 逐条对齐（属主/未使用/未过期/币种）。
 * 列出来一张后端会拒的券，用户点了就是一次失败的下单，而他看不出哪里错了。
 */
const usable = computed(() =>
  coupons.value.filter(
    (c) =>
      c.status === "UNUSED" &&
      !isExpired(c.expireAt) &&
      (!avail.value?.currency || !c.currency || c.currency === avail.value.currency),
  ),
);

function isExpired(expireAt: string) {
  if (!expireAt) return false; // 空 = 不限期，与后端同一口径
  const d = new Date(expireAt.replace(" ", "T"));
  return !Number.isNaN(d.getTime()) && d.getTime() < Date.now();
}

/** 「减 5 AED」/「省 20%」。折扣率是**付几成**，所以省的是 1-value。 */
function offerText(c: UserCoupon) {
  const main =
    c.tplType === "DISCOUNT"
      ? t("borrow.couponOff", { v: Math.round((1 - c.value) * 100) })
      : t("borrow.couponCut", { v: `${c.value} ${c.currency}` });
  return c.threshold > 0 ? `${main} · ${t("borrow.couponThreshold", { v: `${c.threshold} ${c.currency}` })}` : main;
}

const pickedLabel = computed(() => {
  const c = usable.value.find((x) => x.couponNo === picked.value);
  return c ? offerText(c) : t("borrow.couponNone");
});

function choose(couponNo: string | null) {
  picked.value = couponNo;
  pickerOpen.value = false;
}

/**
 * 借出成功与否，**以服务端的订单状态为准**。
 *
 * 此前这里是 `setTimeout(() => stage = "success", 1000)` —— 下单请求一返回就宣布成功，
 * 与柜机到底弹没弹出**毫无关系**。两种后果都不报错：
 * 宝没出来而界面说成功（用户空手走人，订单在计费）；或者弹出确实失败了，
 * 而端上从头到尾没给过一个「失败」的出口，用户只能反复重扫。
 *
 * 现在轮询订单，进 `IN_USE` 才算成功；超过 `DISPENSE_TIMEOUT_MS` 进失败页。
 * 那两个常量此前定义在 `shared/constants.ts` 里，注释写着「超过触发解冻/关单兜底，
 * C-RT-04」，而**全仓零引用** —— 常量定了，轮询没写。
 *
 * ⚠️ 已知边界：后端 `RentOrderServiceImpl.rent()` 当前**直接把订单置为 IN_USE**
 * （那行注释写着「免押预授权 + 弹仓为骨架」），既不经 DISPENSING、也不等设备回执。
 * 所以接真后端时这里第一轮就成功 —— 本页不再撒谎，但「等柜机回执」这件事
 * 要等后端补上 DISPENSING + EJECT 指令 + 超时撤单才真正成立（见优化方案 §3.1）。
 * mock 层已按目标状态机走（DISPENSING → IN_USE），所以这段逻辑本身可验。
 */
async function submit() {
  if (!agree.value) {
    uni.showToast({ title: t("borrow.agree"), icon: "none" });
    return;
  }
  // 兜一道门禁：正常路径在首页已经拦过，但深链/扫码 URL 也能直接进到这一页
  if (!ensureLoggedIn(`/pages/order/confirm?cabinetNo=${cabinetNo}`)) return;
  stage.value = "dispensing";
  try {
    if (useFree.value) await payment.preAuthFreeDeposit(cabinetNo);
    const receipt = await api.rentOrder({
      cabinetNo,
      useFreeDeposit: useFree.value,
      // 只在真选了券时才带上 —— 传 null 与不传在后端是一个意思，但带着空值更容易被误读成「用券失败」
      ...(picked.value ? { couponNo: picked.value } : {}),
    });
    orderNo.value = receipt.orderNo;
    // 下单只是把弹仓指令发出去 —— 回执里没有「出没出宝」这件事（`RentReceipt` 无 status），
    // 成不成功一律去查订单。模拟回执开着时第一轮就会读到 IN_USE，那也是**读来的**，不是猜的
    waitForDispense();
  } catch (e) {
    stage.value = "confirm";
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

function waitForDispense() {
  const deadline = Date.now() + DISPENSE_TIMEOUT_MS;
  stopPolling();
  pollTimer = setInterval(async () => {
    try {
      const o = await api.getOrder(orderNo.value);
      if (o.status === "IN_USE") {
        stopPolling();
        stage.value = "success";
      } else if (o.status === "EXCEPTION" || o.status === "CLOSED") {
        // 后台已经判成异常/撤单 —— 按它说的来，别继续等
        stopPolling();
        stage.value = "failed";
      } else if (Date.now() > deadline) {
        stopPolling();
        stage.value = "failed";
      }
    } catch {
      // 单次轮询失败不算数（弱网很常见），交给 deadline 收口
      if (Date.now() > deadline) {
        stopPolling();
        stage.value = "failed";
      }
    }
  }, POLL_INTERVAL_MS);
}

function stopPolling() {
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = null;
}
// 页面关了还在轮询 = 白烧请求，而且回调还会去改一个已经不存在的界面
onUnload(stopPolling);

function viewOrder() {
  uni.redirectTo({ url: `/pages/order/detail?orderNo=${orderNo.value}` });
}
/** 换个柜子再试：回首页重新选，而不是对着同一个失败的柜子重试。 */
function tryAnother() {
  uni.reLaunch({ url: "/pages/home/index" });
}
/** 带着这一单去报障 —— 失败页最要紧的出口，人工那边才知道该查哪个柜子。 */
function report() {
  uni.redirectTo({
    url: `/pages/order/feedback?cabinetNo=${cabinetNo}${orderNo.value ? "&orderNo=" + orderNo.value : ""}`,
  });
}
</script>

<template>
  <pb-scaffold :title="$t('borrow.confirm')" show-back>
    <!-- 确认 -->
    <view v-if="stage === 'confirm'" class="px-[32rpx] pt-[24rpx]">
      <pb-card v-if="avail">
        <text class="pb-h2">{{ avail.siteName }}</text>
        <view class="mt-[8rpx] text-[24rpx] text-sub">{{ $t("borrow.cabinet") }} · {{ avail.cabinetNo }}</view>
        <view class="mt-[28rpx] flex flex-col gap-[20rpx]">
          <view class="flex items-center justify-between">
            <text class="text-[26rpx] text-sub">{{ $t("borrow.price") }}</text>
            <view class="flex items-baseline gap-[4rpx]"><pb-amount :value="avail.pricePerHour" size="sm" /><text class="text-[22rpx] text-sub">{{ $t("common.perHour") }}</text></view>
          </view>
          <view class="flex items-center justify-between">
            <text class="text-[26rpx] text-sub">{{ $t("borrow.dailyCap") }}</text>
            <pb-amount :value="avail.dailyCap" size="sm" />
          </view>
          <view class="flex items-center justify-between">
            <text class="text-[26rpx] text-sub">{{ $t("borrow.buyout") }}</text>
            <pb-amount :value="avail.buyoutPrice" size="sm" />
          </view>
        </view>
      </pb-card>

      <!-- 免押 / 押金 -->
      <view class="mt-[24rpx] flex flex-col gap-[16rpx]">
        <view class="pb-opt" :class="{ 'is-on': useFree }" @tap="useFree = true">
          <view class="flex-1">
            <text class="text-[28rpx] font-bold text-ink">{{ $t("borrow.freeDeposit") }}</text>
            <view class="mt-[4rpx] text-[22rpx] text-sub">{{ $t("borrow.freeDepositOn") }}</view>
          </view>
          <view class="pb-radio" :class="{ 'is-on': useFree }" />
        </view>
        <view class="pb-opt" :class="{ 'is-on': !useFree }" @tap="useFree = false">
          <text class="flex-1 text-[28rpx] font-bold text-ink">{{ $t("borrow.depositMode") }}</text>
          <view class="flex items-center gap-[16rpx]">
            <pb-amount v-if="avail" :value="avail.depositAmount" size="sm" />
            <view class="pb-radio" :class="{ 'is-on': !useFree }" />
          </view>
        </view>
      </view>

      <!-- 优惠券。此前这里什么都没有：券领得到、看得见，就是花不掉 -->
      <view class="mt-[24rpx]">
        <view class="pb-opt" @tap="pickerOpen = !pickerOpen">
          <text class="flex-1 text-[28rpx] font-bold text-ink">{{ $t("borrow.coupon") }}</text>
          <text class="text-[24rpx]" :class="picked ? 'text-primary' : 'text-sub'">{{ pickedLabel }}</text>
        </view>
        <view v-if="pickerOpen" class="mt-[12rpx] flex flex-col gap-[12rpx]">
          <view class="pb-opt" :class="{ 'is-on': !picked }" @tap="choose(null)">
            <text class="flex-1 text-[26rpx] text-ink">{{ $t("borrow.couponNone") }}</text>
            <view class="pb-radio" :class="{ 'is-on': !picked }" />
          </view>
          <view
            v-for="c in usable"
            :key="c.couponNo"
            class="pb-opt"
            :class="{ 'is-on': picked === c.couponNo }"
            @tap="choose(c.couponNo)"
          >
            <view class="flex-1">
              <text class="text-[26rpx] font-bold text-ink">{{ c.tplName }}</text>
              <view class="mt-[4rpx] text-[22rpx] text-sub">{{ offerText(c) }}</view>
            </view>
            <view class="pb-radio" :class="{ 'is-on': picked === c.couponNo }" />
          </view>
          <view v-if="!usable.length" class="px-[28rpx] py-[20rpx] text-[24rpx] text-sub">
            {{ $t("borrow.couponEmpty") }}
          </view>
        </view>
      </view>

      <view class="mt-[28rpx] flex items-center gap-[14rpx] px-[8rpx]" @tap="agree = !agree">
        <view class="pb-check" :class="{ 'is-on': agree }"><pb-icon v-if="agree" name="check" :size="24" :stroke="3" /></view>
        <text class="text-[24rpx] text-sub">{{ $t("borrow.agree") }}</text>
      </view>

      <view class="mt-[36rpx]"><pb-button block size="lg" @click="submit">{{ $t("borrow.submit") }}</pb-button></view>
    </view>

    <!-- 弹出中 -->
    <view v-else-if="stage === 'dispensing'" class="flex flex-col items-center justify-center pt-[220rpx]">
      <view class="pb-dispense text-primary"><pb-icon name="battery" :size="140" :stroke="1.6" /></view>
      <text class="mt-[44rpx] text-[32rpx] font-bold text-ink">{{ $t("borrow.dispensing") }}</text>
      <text class="mt-[12rpx] text-[24rpx] text-sub">{{ $t("borrow.dispenseTip") }}</text>
    </view>

    <!-- 没弹出：这一页此前根本不存在，弹出失败的人只能反复重扫 -->
    <view v-else-if="stage === 'failed'" class="flex flex-col items-center px-[56rpx] pt-[200rpx]">
      <view class="pb-bad"><pb-icon name="info" :size="84" :stroke="2.4" /></view>
      <text class="mt-[44rpx] text-[36rpx] font-extrabold text-ink">{{ $t("borrow.failed") }}</text>
      <text class="mt-[16rpx] text-center text-[24rpx] text-sub" style="line-height: 1.7">{{ $t("borrow.failedTip") }}</text>
      <view class="mt-[52rpx] w-full"><pb-button block size="lg" @click="tryAnother">{{ $t("borrow.tryAnother") }}</pb-button></view>
      <view class="mt-[20rpx] w-full"><pb-button block size="lg" type="tonal" @click="report">{{ $t("borrow.reportIt") }}</pb-button></view>
      <view v-if="orderNo" class="mt-[28rpx]" @tap="viewOrder">
        <text class="text-[24rpx] text-primary">{{ $t("borrow.viewOrder") }}</text>
      </view>
    </view>

    <!-- 成功 -->
    <view v-else class="flex flex-col items-center justify-center pt-[220rpx]">
      <view class="pb-ok"><pb-icon name="check" :size="88" :stroke="2.6" /></view>
      <text class="mt-[44rpx] text-[36rpx] font-extrabold text-ink">{{ $t("borrow.success") }}</text>
      <text class="mt-[12rpx] text-[24rpx] text-sub">{{ $t("borrow.successTip") }}</text>
      <view class="mt-[52rpx] w-[62%]"><pb-button block size="lg" @click="viewOrder">{{ $t("borrow.viewOrder") }}</pb-button></view>
    </view>
  </pb-scaffold>
</template>

<style scoped>
.pb-opt {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 28rpx;
  border-radius: 26rpx;
  background: var(--pb-surface);
  box-shadow: var(--pb-shadow);
  transition: background 0.18s;
}
.pb-opt.is-on { background: var(--pb-primary-tint); box-shadow: none; }
.pb-radio {
  width: 40rpx;
  height: 40rpx;
  border-radius: 9999px;
  border: 3rpx solid var(--pb-sub);
  box-sizing: border-box;
  position: relative;
  flex-shrink: 0;
}
.pb-radio.is-on { border-color: var(--pb-primary); }
.pb-radio.is-on::after {
  content: "";
  position: absolute;
  inset: 8rpx;
  border-radius: 9999px;
  background: var(--pb-primary);
}
.pb-check {
  width: 40rpx;
  height: 40rpx;
  border-radius: 12rpx;
  border: 3rpx solid var(--pb-sub);
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--pb-on-primary);
  flex-shrink: 0;
}
.pb-check.is-on { background: var(--pb-primary); border-color: var(--pb-primary); }
.pb-dispense { display: inline-block; animation: pbbounce 1s ease-in-out infinite; }
.pb-bad {
  width: 168rpx;
  height: 168rpx;
  border-radius: 9999px;
  background: var(--pb-faint);
  color: var(--pb-sub);
  display: flex;
  align-items: center;
  justify-content: center;
}
@keyframes pbbounce { 0%, 100% { transform: translateY(0); } 50% { transform: translateY(-16rpx); } }
.pb-ok {
  width: 168rpx;
  height: 168rpx;
  border-radius: 9999px;
  background: var(--pb-primary);
  color: var(--pb-on-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 16rpx 44rpx var(--pb-primary-tint);
}
</style>
