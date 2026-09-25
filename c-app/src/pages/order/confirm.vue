<script setup lang="ts">
// 借出确认 → 下单 → 等弹出(dispensing) → 借出成功。免押/押金二选一（端侧 PaymentPort，MVP Stub）。
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "@/api";
import { t } from "@/i18n";
import type { CabinetAvailability, UserCoupon } from "@/types";
import { payment } from "@/ports/payment";

const avail = ref<CabinetAvailability | null>(null);
const agree = ref(true);
const useFree = ref(true);
const coupons = ref<UserCoupon[]>([]);
const picked = ref<string | null>(null);
const pickerOpen = ref(false);
const stage = ref<"confirm" | "dispensing" | "success">("confirm");
const orderNo = ref("");
let cabinetNo = "";

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

async function submit() {
  if (!agree.value) {
    uni.showToast({ title: t("borrow.agree"), icon: "none" });
    return;
  }
  stage.value = "dispensing";
  try {
    if (useFree.value) await payment.preAuthFreeDeposit(cabinetNo);
    const order = await api.rentOrder({
      cabinetNo,
      useFreeDeposit: useFree.value,
      // 只在真选了券时才带上 —— 传 null 与不传在后端是一个意思，但带着空值更容易被误读成「用券失败」
      ...(picked.value ? { couponNo: picked.value } : {}),
    });
    orderNo.value = order.orderNo;
    setTimeout(() => (stage.value = "success"), 1000);
  } catch (e) {
    stage.value = "confirm";
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}
function viewOrder() {
  uni.redirectTo({ url: `/pages/order/detail?orderNo=${orderNo.value}` });
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
