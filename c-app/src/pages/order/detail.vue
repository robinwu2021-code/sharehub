<script setup lang="ts">
// 订单详情：票根卡（青绿渐变头 + 大金额 + 凹口虚线分隔）+ 租借信息 + 费用明细 + 状态时间线 + 报障入口。
import { ref, computed } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "@/api";
import type { RentOrder } from "@/types";
import { timeOf, dateTimeOf } from "@/shared/format";

const order = ref<RentOrder | null>(null);
const buying = ref(false);
const isInUse = computed(() => order.value?.status === "IN_USE" || order.value?.status === "DISPENSING");

onLoad(async (q) => {
  order.value = await api.getOrder((q?.orderNo as string) || "");
});

function report() {
  if (order.value) uni.navigateTo({ url: `/pages/order/feedback?orderNo=${order.value.orderNo}` });
}
async function buyout() {
  if (!order.value) return;
  buying.value = true;
  try {
    order.value = await api.buyout(order.value.orderNo);
  } finally {
    buying.value = false;
  }
}
</script>

<template>
  <pb-scaffold :title="$t('orders.detail')" show-back>
    <view v-if="order" class="px-[32rpx] pt-[24rpx] pb-[40rpx]">
      <!-- 票根卡 -->
      <view class="pb-ticket">
        <!-- 渐变头：状态 + 大金额 -->
        <view class="pb-ticket__head">
          <view class="flex items-center justify-between">
            <text class="pb-ticket__status">{{ $t("status." + order.status) }}</text>
            <text class="pb-ticket__date">{{ dateTimeOf(order.startAt) }}</text>
          </view>
          <view class="pb-ticket__amount pb-num">
            <text class="pb-ticket__cur">{{ $t("common.currency") }}</text>
            <text>{{ order.amount.toFixed(2) }}</text>
          </view>
          <view class="pb-ticket__no">{{ order.orderNo }}</view>
          <view v-if="isInUse" class="mt-[12rpx] flex items-center gap-[8rpx] text-[22rpx] opacity-80">
            <pb-icon name="info" :size="26" /><text>{{ $t("orders.capNote") }}</text>
          </view>
        </view>

        <!-- 凹口 + 虚线撕缝 -->
        <view class="pb-ticket__seam">
          <view class="pb-ticket__notch pb-ticket__notch--l" />
          <view class="pb-ticket__perf" />
          <view class="pb-ticket__notch pb-ticket__notch--r" />
        </view>

        <!-- 票根身：租借信息 / 费用明细 / 时间线 -->
        <view class="pb-ticket__body">
          <!-- 租借信息 -->
          <view class="pb-sec__title">{{ $t("orders.rentInfo") }}</view>
          <view class="flex flex-col gap-[16rpx]">
            <view class="pb-kv"><text class="pb-kv__k">{{ $t("orders.borrowAt") }}</text><text class="pb-kv__v">{{ order.siteNameBorrow }}</text></view>
            <view v-if="order.siteNameReturn" class="pb-kv"><text class="pb-kv__k">{{ $t("orders.returnAt") }}</text><text class="pb-kv__v">{{ order.siteNameReturn }}</text></view>
            <view class="pb-kv"><text class="pb-kv__k">{{ $t("orders.duration") }}</text><text class="pb-kv__v">{{ order.durationMin }} {{ $t("common.min") }}</text></view>
            <view class="pb-kv"><text class="pb-kv__k">{{ $t("orders.rentTime") }}</text><text class="pb-kv__v">{{ dateTimeOf(order.startAt) }}</text></view>
            <view v-if="order.powerBankNo" class="pb-kv"><text class="pb-kv__k">{{ $t("orders.deviceNo") }}</text><text class="pb-kv__v pb-num">{{ order.powerBankNo }}</text></view>
          </view>

          <view class="pb-dash" />

          <!-- 费用明细 -->
          <view class="pb-sec__title">{{ $t("orders.feeDetail") }}</view>
          <view class="flex flex-col gap-[16rpx]">
            <view v-for="(f, i) in order.fees" :key="i" class="pb-kv">
              <text class="pb-kv__k">{{ f.label }}</text>
              <pb-amount :value="f.amount" size="sm" />
            </view>
            <view class="pb-kv pb-kv--total">
              <text class="text-[28rpx] font-semibold text-ink">{{ $t("orders.total") }}</text>
              <pb-amount :value="order.amount" size="md" />
            </view>
          </view>

          <view class="pb-dash" />

          <!-- 状态时间线 -->
          <view class="pb-sec__title">{{ $t("orders.timeline") }}</view>
          <view class="flex flex-col gap-[22rpx]">
            <view v-for="(step, i) in order.timeline" :key="i" class="flex items-center gap-[16rpx]">
              <view class="pb-dot" />
              <text class="flex-1 text-[26rpx] text-ink">{{ $t("status." + step.status) }}</text>
              <text class="text-[24rpx] text-sub">{{ timeOf(step.at) }}</text>
            </view>
          </view>
        </view>
      </view>

      <!-- 使用中：买断 -->
      <view v-if="isInUse" class="mt-[24rpx]">
        <pb-button block :loading="buying" @click="buyout">{{ $t("orders.buyout") }}</pb-button>
      </view>

      <view class="mt-[20rpx]">
        <pb-button block type="tonal" @click="report">{{ $t("orders.report") }}</pb-button>
      </view>
    </view>
  </pb-scaffold>
</template>

<style scoped>
.pb-ticket {
  border-radius: 32rpx;
  box-shadow: var(--pb-shadow);
}
.pb-ticket__head {
  padding: 36rpx 36rpx 40rpx;
  border-radius: 32rpx 32rpx 0 0;
  color: var(--pb-on-primary);
  background: linear-gradient(135deg, var(--pb-primary), color-mix(in srgb, var(--pb-primary) 72%, #000 14%));
}
.pb-ticket__status {
  padding: 5rpx 20rpx;
  border-radius: 9999px;
  font-size: 22rpx;
  font-weight: 700;
  color: var(--pb-on-primary);
  background: rgba(255, 255, 255, 0.22);
}
.pb-ticket__date { font-size: 24rpx; opacity: 0.85; }
.pb-ticket__amount { margin-top: 20rpx; font-size: 68rpx; font-weight: 800; line-height: 1; }
.pb-ticket__cur { font-size: 0.42em; font-weight: 700; opacity: 0.85; margin-inline-end: 0.4em; }
.pb-ticket__no { margin-top: 14rpx; font-size: 24rpx; opacity: 0.8; }
/* 撕缝：两侧凹口 + 中间虚线 */
.pb-ticket__seam {
  position: relative;
  height: 40rpx;
  background: var(--pb-surface);
  display: flex;
  align-items: center;
}
.pb-ticket__notch {
  position: absolute;
  top: 50%;
  width: 40rpx; height: 40rpx;
  border-radius: 9999px;
  background: var(--pb-bg);
  transform: translateY(-50%);
}
.pb-ticket__notch--l { left: -20rpx; }
.pb-ticket__notch--r { right: -20rpx; }
.pb-ticket__perf {
  flex: 1;
  margin: 0 30rpx;
  border-top: 2rpx dashed color-mix(in srgb, var(--pb-sub) 32%, transparent);
}
.pb-ticket__body {
  padding: 8rpx 36rpx 40rpx;
  background: var(--pb-surface);
  border-radius: 0 0 32rpx 32rpx;
}
.pb-sec__title { margin: 24rpx 0 20rpx; font-size: 24rpx; font-weight: 600; color: var(--pb-sub); }
.pb-kv { display: flex; align-items: center; justify-content: space-between; gap: 24rpx; }
.pb-kv__k { font-size: 26rpx; color: var(--pb-sub); flex-shrink: 0; }
.pb-kv__v { font-size: 26rpx; color: var(--pb-ink); text-align: end; }
.pb-kv--total { margin-top: 8rpx; }
.pb-dash { margin: 28rpx 0 4rpx; border-top: 2rpx dashed color-mix(in srgb, var(--pb-sub) 32%, transparent); }
.pb-dot { width: 20rpx; height: 20rpx; border-radius: 9999px; background: var(--pb-primary); }
</style>
