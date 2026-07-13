<script setup lang="ts">
// 借出确认 → 下单 → 等弹出(dispensing) → 借出成功。免押/押金二选一（端侧 PaymentPort，MVP Stub）。
import { ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "@/api";
import { t } from "@/i18n";
import type { CabinetAvailability } from "@/types";
import { payment } from "@/ports/payment";

const avail = ref<CabinetAvailability | null>(null);
const agree = ref(true);
const useFree = ref(true);
const stage = ref<"confirm" | "dispensing" | "success">("confirm");
const orderNo = ref("");
let cabinetNo = "";

onLoad((q) => {
  cabinetNo = (q?.cabinetNo as string) || "";
  load();
});
async function load() {
  avail.value = await api.cabinetAvailability(cabinetNo);
}

async function submit() {
  if (!agree.value) {
    uni.showToast({ title: t("borrow.agree"), icon: "none" });
    return;
  }
  stage.value = "dispensing";
  try {
    if (useFree.value) await payment.preAuthFreeDeposit(cabinetNo);
    const order = await api.rentOrder({ cabinetNo, useFreeDeposit: useFree.value });
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
