<script setup lang="ts">
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import type { RentOrder } from "@/api/types";
import { useAuthStore } from "@/stores/auth";

const { t } = useI18n();
const auth = useAuthStore();
const list = ref<RentOrder[]>([]);

async function load() {
  if (!auth.isLoggedIn) {
    list.value = [];
    return;
  }
  const res = await api.listOrders({ size: 30 });
  list.value = res.list;
}
onShow(load);

function open(no: string) {
  uni.navigateTo({ url: `/pages/order/detail?orderNo=${no}` });
}
</script>

<template>
  <view class="p-3">
    <view v-if="!auth.isLoggedIn" class="card text-center text-gray-400 mt-6">
      {{ t("common.login") }} →
      <text class="text-brand" @click="uni.navigateTo({ url: '/pages/login/login' })">{{ t("common.login") }}</text>
    </view>
    <view v-else>
      <view
        v-for="o in list"
        :key="o.orderNo"
        class="card mt-2 active:opacity-80"
        @click="open(o.orderNo)"
      >
        <view class="row-between">
          <text class="font-medium">{{ o.orderNo }}</text>
          <text class="text-sm" :class="o.status === 'IN_USE' ? 'text-brand' : 'text-gray-400'">
            {{ t(`status.${o.status}`) }}
          </text>
        </view>
        <view class="row-between mt-1">
          <text class="text-xs text-gray-400 truncate flex-1">{{ o.siteName }}</text>
          <text class="text-sm">{{ o.currency }} {{ o.feeAmount.toFixed(2) }}</text>
        </view>
      </view>
      <view v-if="!list.length" class="text-center text-gray-400 mt-8">—</view>
    </view>
  </view>
</template>
