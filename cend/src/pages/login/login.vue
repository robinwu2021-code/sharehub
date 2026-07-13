<script setup lang="ts">
import { ref } from "vue";
import { useI18n } from "vue-i18n";
import { useAuthStore } from "@/stores/auth";
import { LOGIN_CHANNEL } from "@/config";

const { t } = useI18n();
const auth = useAuthStore();
const phone = ref("+971500000001");
const loading = ref(false);

async function doLogin() {
  loading.value = true;
  try {
    await auth.login({ phone: phone.value });
    uni.showToast({ title: "OK", icon: "success" });
    setTimeout(() => uni.navigateBack(), 400);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <view class="p-4">
    <view class="text-center mt-8 mb-6">
      <text class="text-3xl">🔋</text>
      <text class="block text-xl font-bold mt-2">{{ t("app.name") }}</text>
    </view>
    <view class="card">
      <text class="text-xs text-gray-400">{{ LOGIN_CHANNEL === "MP" ? "WeChat" : "Phone" }}</text>
      <input
        v-model="phone"
        class="mt-1 h-10 w-full border-b border-gray-100 text-base"
        placeholder="+9715…"
      />
      <view
        class="mt-5 bg-brand text-white rounded-xl py-3 text-center font-semibold active:opacity-80"
        :class="loading ? 'opacity-60' : ''"
        @click="doLogin"
      >
        {{ t("common.login") }}
      </view>
    </view>
    <text class="block text-center text-xs text-gray-400 mt-4">
      App: OTP / Apple / Google · 小程序: 微信一键
    </text>
  </view>
</template>
