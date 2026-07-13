<script setup lang="ts">
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import type { Wallet } from "@/api/types";
import { useAuthStore } from "@/stores/auth";
import { setLocale, isRTL } from "@/i18n";

const { t, locale } = useI18n();
const auth = useAuthStore();
const wallet = ref<Wallet | null>(null);

async function load() {
  if (!auth.isLoggedIn) return;
  wallet.value = await api.getWallet();
}
onShow(load);

function toggleLang() {
  const next = locale.value === "ar" ? "en" : "ar";
  setLocale(next as "en" | "ar");
  // #ifdef H5
  if (typeof document !== "undefined") document.documentElement.setAttribute("dir", isRTL() ? "rtl" : "ltr");
  // #endif
}
function login() {
  uni.navigateTo({ url: "/pages/login/login" });
}
function logout() {
  auth.logout();
  wallet.value = null;
}
</script>

<template>
  <view class="p-3">
    <view class="card">
      <view v-if="auth.isLoggedIn">
        <view class="row-between">
          <text class="text-lg font-semibold">{{ auth.profile?.nickname }}</text>
          <text class="text-xs text-gray-400">{{ auth.profile?.phone }}</text>
        </view>
        <view class="mt-2 row-between">
          <text class="text-gray-500 text-sm">{{ t("me.credit") }}</text>
          <text class="text-brand font-bold">{{ auth.profile?.creditScore }}</text>
        </view>
      </view>
      <view v-else class="text-center py-2 active:opacity-70" @click="login">
        <text class="text-brand font-semibold">{{ t("common.login") }}</text>
      </view>
    </view>

    <view v-if="auth.isLoggedIn && wallet" class="card mt-3">
      <text class="text-gray-500 text-sm">{{ t("me.wallet") }} ({{ wallet.currency }})</text>
      <view class="flex mt-2">
        <view class="flex-1 text-center">
          <text class="block text-lg font-bold">{{ wallet.balance }}</text>
          <text class="text-xs text-gray-400">{{ t("me.balance") }}</text>
        </view>
        <view class="flex-1 text-center">
          <text class="block text-lg font-bold">{{ wallet.bonus }}</text>
          <text class="text-xs text-gray-400">{{ t("me.bonus") }}</text>
        </view>
        <view class="flex-1 text-center">
          <text class="block text-lg font-bold">{{ wallet.frozen }}</text>
          <text class="text-xs text-gray-400">{{ t("me.deposit") }}</text>
        </view>
      </view>
    </view>

    <view class="card mt-3 row-between active:opacity-70" @click="toggleLang">
      <text class="text-gray-600">{{ t("me.language") }}</text>
      <text class="text-brand">{{ locale === "ar" ? "العربية" : "English" }} ⇄</text>
    </view>

    <view
      v-if="auth.isLoggedIn"
      class="card mt-3 text-center text-red-500 active:opacity-70"
      @click="logout"
    >
      {{ t("common.logout") }}
    </view>
  </view>
</template>
