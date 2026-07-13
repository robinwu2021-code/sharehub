<script setup lang="ts">
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api, IS_MOCK } from "@/api";
import type { NearbyCabinet } from "@/api/types";
import { useAuthStore } from "@/stores/auth";

const { t } = useI18n();
const auth = useAuthStore();
const list = ref<NearbyCabinet[]>([]);
const loading = ref(false);

async function load() {
  loading.value = true;
  try {
    const res = await api.listNearby({ size: 20 });
    list.value = res.list;
  } finally {
    loading.value = false;
  }
}
onShow(load);

function onScan() {
  // #ifdef APP-PLUS || MP-WEIXIN
  uni.scanCode({
    success: (r) => goConfirm(r.result || "CAB1000"),
    fail: () => uni.showToast({ title: "已取消", icon: "none" }),
  });
  // #endif
  // #ifdef H5
  // H5 无摄像头扫码，取最近可借柜机演示借出
  const c = list.value.find((x) => x.available > 0) || list.value[0];
  if (c) goConfirm(c.cabinetNo);
  // #endif
}

function goConfirm(cabinetNo: string) {
  if (!auth.isLoggedIn) {
    uni.navigateTo({ url: "/pages/login/login" });
    return;
  }
  uni.navigateTo({ url: `/pages/order/confirm?cabinetNo=${cabinetNo}` });
}
</script>

<template>
  <view class="min-h-full pb-4">
    <view class="bg-brand px-4 pt-3 pb-6 text-white">
      <view class="row-between">
        <text class="text-lg font-bold">{{ t("nearby.title") }}</text>
        <text v-if="IS_MOCK" class="text-xs bg-white/25 px-2 py-1 rounded-full">Mock</text>
      </view>
      <view
        class="mt-4 bg-white text-brand rounded-xl py-3 text-center font-semibold active:opacity-80"
        @click="onScan"
      >
        📷 {{ t("nearby.scan") }}
      </view>
    </view>

    <view class="px-3 -mt-3">
      <view v-if="loading" class="card mt-2 text-center text-gray-400">…</view>
      <view
        v-for="c in list"
        :key="c.cabinetNo"
        class="card mt-2 row-between active:opacity-80"
        @click="goConfirm(c.cabinetNo)"
      >
        <view class="flex-1 min-w-0">
          <text class="font-medium block truncate">{{ c.siteName }}</text>
          <text class="text-xs text-gray-400 block truncate">{{ c.address }}</text>
          <text class="text-xs text-gray-500">
            {{ c.distanceKm }} {{ t("nearby.km") }} · {{ t("nearby.available") }}
            <text class="text-brand font-semibold">{{ c.available }}</text>
            · {{ t("nearby.returnable") }} {{ c.returnableSlots }}
          </text>
        </view>
        <text class="text-brand text-2xl ml-2">›</text>
      </view>
    </view>
  </view>
</template>
