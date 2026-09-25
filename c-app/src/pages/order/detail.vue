<script setup lang="ts">
// 订单详情：状态头 + 借还网点 + 费用明细 + 状态时间线 + 报障入口。
import { ref, computed } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "@/api";
import type { ConsumerOrder, OrderStatus } from "@/types";
import { timeOf } from "@/shared/format";

const order = ref<ConsumerOrder | null>(null);
const buying = ref(false);
const isInUse = computed(() => order.value?.status === "IN_USE" || order.value?.status === "DISPENSING");
const tagType: Record<OrderStatus, "primary" | "success" | "warning" | "danger" | "neutral"> = {
  CREATED: "neutral",
  DISPENSING: "warning",
  IN_USE: "primary",
  RETURNED: "warning",
  SETTLED: "success",
  CLOSED: "neutral",
  EXCEPTION: "danger",
};

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
    <view v-if="order" class="px-[32rpx] pt-[24rpx]">
      <!-- 状态头 -->
      <pb-card tint="primary">
        <view class="flex items-center justify-between">
          <pb-tag :type="tagType[order.status]">{{ $t("status." + order.status) }}</pb-tag>
          <pb-amount :value="order.feeAmount" size="lg" />
        </view>
        <view class="mt-[10rpx] text-[24rpx] text-sub">{{ order.orderNo }}</view>
        <view v-if="isInUse" class="mt-[16rpx] flex items-center gap-[8rpx] text-[22rpx] text-sub">
          <pb-icon name="info" :size="26" /><text>{{ $t("orders.capNote") }}</text>
        </view>
      </pb-card>

      <!-- 使用中：买断 -->
      <view v-if="isInUse" class="mt-[20rpx]">
        <pb-button block :loading="buying" @click="buyout">{{ $t("orders.buyout") }}</pb-button>
      </view>

      <!-- 借 / 还 网点 -->
      <view class="mt-[20rpx]">
        <pb-card :pad="false">
          <view class="px-[28rpx]">
            <pb-cell icon="battery" :title="$t('orders.borrowAt')" :value="order.siteName ?? order.locationName ?? order.cabinetNo" />
            <pb-cell v-if="order.returnCabinetNo" icon="pin" :title="$t('orders.returnAt')" :value="order.returnSiteName ?? order.returnCabinetNo" />
            <pb-cell icon="clock" :title="$t('orders.duration')" :value="`${order.durationMin} ${$t('common.min')}`" />
          </view>
        </pb-card>
      </view>

      <!-- 费用明细 -->
      <view class="mt-[20rpx]">
        <text class="mb-[12rpx] block text-[24rpx] text-sub">{{ $t("orders.feeDetail") }}</text>
        <pb-card>
          <view class="flex flex-col gap-[16rpx]">
            <view v-for="(f, i) in order.fees" :key="i" class="flex items-center justify-between">
              <text class="text-[26rpx] text-sub">{{ $t("fee." + f.type) }}</text>
              <pb-amount :value="f.amount" size="sm" />
            </view>
            <view class="mt-[8rpx] flex items-center justify-between border-t-0">
              <text class="text-[28rpx] font-semibold text-ink">{{ $t("orders.total") }}</text>
              <pb-amount :value="order.feeAmount" size="md" />
            </view>
          </view>
        </pb-card>
      </view>

      <!-- 状态时间线（色块圆点，无连线） -->
      <view class="mt-[20rpx]">
        <text class="mb-[12rpx] block text-[24rpx] text-sub">{{ $t("orders.timeline") }}</text>
        <pb-card>
          <view class="flex flex-col gap-[22rpx]">
            <view v-for="(step, i) in order.timeline" :key="i" class="flex items-center gap-[16rpx]">
              <view class="pb-dot" />
              <text class="flex-1 text-[26rpx] text-ink">{{ $t("status." + step.status) }}</text>
              <text class="text-[24rpx] text-sub">{{ timeOf(step.at) }}</text>
            </view>
          </view>
        </pb-card>
      </view>

      <view class="mt-[32rpx] mb-[20rpx]">
        <pb-button block type="tonal" @click="report">{{ $t("orders.report") }}</pb-button>
      </view>
    </view>
  </pb-scaffold>
</template>

<style scoped>
.pb-dot { width: 20rpx; height: 20rpx; border-radius: 9999px; background: var(--pb-primary); }
</style>
