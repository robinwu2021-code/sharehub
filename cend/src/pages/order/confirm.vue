<script setup lang="ts">
import { ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import type { NearbyCabinet } from "@/api/types";

const { t } = useI18n();
const cabinetNo = ref("");
const site = ref<NearbyCabinet | null>(null);
const freeDeposit = ref(true);
const submitting = ref(false);

onLoad(async (opts) => {
  cabinetNo.value = opts?.cabinetNo || "";
  const res = await api.listNearby({ size: 50 });
  site.value = res.list.find((c) => c.cabinetNo === cabinetNo.value) || null;
});

async function borrow() {
  submitting.value = true;
  uni.showLoading({ title: t("rent.dispensing") });
  try {
    const { orderNo } = await api.rent(cabinetNo.value);
    uni.hideLoading();
    uni.showToast({ title: t("rent.success"), icon: "success" });
    setTimeout(() => uni.redirectTo({ url: `/pages/order/detail?orderNo=${orderNo}` }), 500);
  } catch (e) {
    uni.hideLoading();
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <view class="p-3">
    <view class="card">
      <text class="font-semibold block">{{ site?.siteName || cabinetNo }}</text>
      <text class="text-xs text-gray-400 block">{{ site?.address }}</text>
      <text class="text-xs text-gray-400 block mt-1">{{ cabinetNo }}</text>
    </view>

    <view class="card mt-3">
      <view class="row-between py-1">
        <text class="text-gray-500">{{ t("rent.price") }}</text>
        <text>{{ site?.priceBrief }}</text>
      </view>
      <view class="row-between py-1">
        <text class="text-gray-500">{{ freeDeposit ? t("rent.freeDeposit") : t("rent.deposit") }}</text>
        <text>{{ freeDeposit ? "AED 0" : "AED 50" }}</text>
      </view>
    </view>

    <view
      class="mt-6 bg-brand text-white rounded-xl py-3 text-center font-semibold active:opacity-80"
      :class="submitting ? 'opacity-60' : ''"
      @click="borrow"
    >
      {{ t("rent.borrow") }}
    </view>
  </view>
</template>
