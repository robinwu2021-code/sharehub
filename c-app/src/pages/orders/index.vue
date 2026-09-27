<script setup lang="ts">
import { ref, computed } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import type { ConsumerOrder, OrderStatus } from "@/types";
import { elapsedMin, durationText } from "@/shared/format";

const { t } = useI18n();
const all = ref<ConsumerOrder[]>([]);
const loading = ref(true);
const filter = ref("all");

const ONGOING: OrderStatus[] = ["CREATED", "DISPENSING", "IN_USE", "RETURNED"];
const list = computed(() =>
  filter.value === "ongoing" ? all.value.filter((o) => ONGOING.includes(o.status)) : all.value,
);
const options = computed(() => [
  { label: t("orders.all"), value: "all" },
  { label: t("orders.ongoing"), value: "ongoing" },
]);

const tagType: Record<OrderStatus, "primary" | "success" | "warning" | "danger" | "neutral"> = {
  CREATED: "neutral",
  DISPENSING: "warning",
  IN_USE: "primary",
  RETURNED: "warning",
  SETTLED: "success",
  CLOSED: "neutral",
  EXCEPTION: "danger",
};

async function load() {
  loading.value = true;
  try {
    const r = await api.listOrders();
    all.value = r.list;
  } finally {
    loading.value = false;
  }
}
onShow(load);

function detail(o: ConsumerOrder) {
  uni.navigateTo({ url: `/pages/order/detail?orderNo=${o.orderNo}` });
}
</script>

<template>
  <pb-scaffold tab="orders">
    <view class="px-[32rpx] pt-[40rpx]">
      <text class="pb-h1">{{ $t("orders.title") }}</text>

      <view class="mt-[24rpx]"><pb-segmented v-model="filter" :options="options" /></view>

      <view class="mt-[24rpx] flex flex-col gap-[24rpx]">
        <view
          v-for="(o, i) in list"
          :key="o.orderNo"
          class="pb-rise"
          :style="{ animationDelay: i * 50 + 'ms' }"
          @tap="detail(o)"
        >
          <pb-card>
            <view class="flex items-center justify-between">
              <text class="text-[30rpx] font-bold text-ink">{{ o.siteName ?? o.locationName ?? o.cabinetNo }}</text>
              <pb-tag :type="tagType[o.status]">{{ $t("status." + o.status) }}</pb-tag>
            </view>
            <view class="mt-[8rpx] text-[24rpx] text-sub">{{ o.orderNo }}</view>
            <!-- 进行中的行：时长按 rentStartAt 现算，金额**不显示** ——
                 列表投影不带试算（逐单跑计价链是 N+1），而 feeAmount 此时恒为 0，
                 印一个「AED 0.00」在那里读起来像这次免费 -->
            <view class="mt-[18rpx] flex items-center justify-between">
              <text class="text-[24rpx] text-sub">
                {{ $t("orders.duration") }} ·
                {{ ONGOING.includes(o.status) ? durationText(elapsedMin(o.rentStartAt) ?? o.durationMin) : o.durationMin + " " + $t("common.min") }}
              </text>
              <pb-amount v-if="!ONGOING.includes(o.status)" :value="o.feeAmount" size="sm" />
              <text v-else class="text-[24rpx] text-sub">{{ $t("orders.inUseNoFeeYet") }}</text>
            </view>
          </pb-card>
        </view>
        <pb-empty v-if="!loading && !list.length" :text="$t('orders.none')" />
      </view>
    </view>
  </pb-scaffold>
</template>
