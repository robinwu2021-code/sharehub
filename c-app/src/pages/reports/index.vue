<script setup lang="ts">
/*
 * 我的报障（进度）。
 *
 * 后端 `GET /mp/user/reports` 与 `/{reportNo}` 一直在，但 C 端从来没有这一页 ——
 * 于是提交报障之后用户**再也看不到它**：不知道受理了没有、有没有转成工单或退款。
 * 唯一能做的事是再报一次，而那只会在人工队列里多一条重复单。
 *
 * 展示的关键不是「状态」三个字，而是**处置去向**：woNo / refundNo 决定了
 * 「在修」还是「在退钱」，这两者对用户意味着完全不同的等待。
 */
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import type { CsTicket } from "@/types";

const list = ref<CsTicket[]>([]);
const loading = ref(true);

const tagType: Record<CsTicket["status"], "primary" | "warning" | "neutral"> = {
  OPEN: "warning",
  PROCESSING: "primary",
  CLOSED: "neutral",
};

async function load() {
  loading.value = true;
  try {
    const r = await api.listReports({ size: 50 });
    list.value = r.list;
  } finally {
    loading.value = false;
  }
}
onShow(load);
</script>

<template>
  <pb-scaffold :title="$t('reports.title')" show-back>
    <view class="flex flex-col gap-[20rpx] px-[32rpx] pt-[24rpx]">
      <pb-card
        v-for="(k, i) in list"
        :key="k.ticketNo"
        class="pb-rise"
        :style="{ animationDelay: i * 50 + 'ms' }"
      >
        <view class="flex items-start justify-between">
          <view class="flex-1">
            <text class="text-[29rpx] font-bold text-ink">{{ k.issue || k.ticketNo }}</text>
            <view class="mt-[6rpx] text-[22rpx] text-sub">{{ k.ticketNo }} · {{ k.createdAt }}</view>
          </view>
          <pb-tag :type="tagType[k.status]">{{ $t("reports." + k.status) }}</pb-tag>
        </view>

        <!-- 去向。只显示状态的话，「处理中」对用户等于什么都没说 -->
        <view class="mt-[20rpx] flex flex-col gap-[8rpx]">
          <view v-if="k.woNo" class="pb-row">
            <pb-icon name="receipt" :size="32" class="text-sub" />
            <text class="text-[24rpx] text-ink">{{ $t("reports.toWorkOrder", { no: k.woNo }) }}</text>
          </view>
          <view v-if="k.refundNo" class="pb-row">
            <pb-icon name="wallet" :size="32" class="text-sub" />
            <text class="text-[24rpx] text-ink">{{ $t("reports.toRefund", { no: k.refundNo }) }}</text>
          </view>
          <view v-if="k.orderNo" class="pb-row">
            <pb-icon name="zap" :size="32" class="text-sub" />
            <text class="text-[24rpx] text-ink">{{ $t("reports.onOrder", { no: k.orderNo }) }}</text>
          </view>
          <!-- 既没工单也没退款、状态却是处理中 = 在人工队列里排着，这句要说出来 -->
          <view v-if="!k.woNo && !k.refundNo && k.status !== 'CLOSED'" class="pb-row">
            <pb-icon name="clock" :size="32" class="text-sub" />
            <text class="text-[24rpx] text-sub">{{ $t("reports.inQueue") }}</text>
          </view>
        </view>
      </pb-card>
      <pb-empty v-if="!loading && !list.length" icon="info" :text="$t('reports.none')" />
    </view>
  </pb-scaffold>
</template>

<style scoped>
.pb-row { display: flex; align-items: center; gap: 12rpx; }
</style>
