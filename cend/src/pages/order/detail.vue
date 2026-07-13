<script setup lang="ts">
import { ref, computed } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import type { RentOrder } from "@/api/types";

const { t } = useI18n();
const order = ref<RentOrder | null>(null);
const orderNo = ref("");

const liveMinutes = computed(() => {
  const o = order.value;
  if (!o || o.status !== "IN_USE" || !o.rentStartAt) return o?.durationMin ?? 0;
  return Math.max(1, Math.round((Date.now() - new Date(o.rentStartAt).getTime()) / 60000));
});

async function load() {
  order.value = await api.getOrder(orderNo.value);
}
onLoad((opts) => {
  orderNo.value = opts?.orderNo || "";
  load();
});

async function doReturn() {
  uni.showLoading({ title: "…" });
  try {
    order.value = await api.returnOrder(orderNo.value);
    uni.hideLoading();
    uni.showToast({ title: t("order.returned"), icon: "success" });
  } catch (e) {
    uni.hideLoading();
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}
function goReport() {
  uni.navigateTo({ url: `/pages/report/report?orderNo=${orderNo.value}` });
}
</script>

<template>
  <view class="p-3" v-if="order">
    <view class="card row-between">
      <text class="font-semibold">{{ order.orderNo }}</text>
      <text class="text-brand text-sm">{{ t(`status.${order.status}`) }}</text>
    </view>

    <view class="card mt-3">
      <view class="row-between py-1"><text class="text-gray-500">{{ order.siteName }}</text><text>{{ order.cabinetNo }}</text></view>
      <view class="row-between py-1"><text class="text-gray-500">{{ t("order.duration") }}</text><text>{{ liveMinutes }} {{ t("order.min") }}</text></view>
      <view class="row-between py-1"><text class="text-gray-500">{{ t("order.fee") }}</text><text class="font-semibold">{{ order.currency }} {{ order.feeAmount.toFixed(2) }}</text></view>
      <view class="row-between py-1"><text class="text-gray-500">{{ t("me.deposit") }}</text><text>{{ order.currency }} {{ order.depositAmount }}</text></view>
    </view>

    <view
      v-if="order.status === 'IN_USE'"
      class="mt-6 bg-brand text-white rounded-xl py-3 text-center font-semibold active:opacity-80"
      @click="doReturn"
    >
      {{ t("order.return") }}
    </view>
    <view class="mt-3 text-center text-gray-400 text-sm active:opacity-70" @click="goReport">
      {{ t("order.report") }}
    </view>
  </view>
</template>
