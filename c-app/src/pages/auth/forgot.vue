<script setup lang="ts">
import { ref } from "vue";
import { api } from "@/api";
import { t } from "@/i18n";
import { DEFAULT_COUNTRY } from "@/shared/countries";
import type { Country } from "@/types";

const country = ref<Country>(DEFAULT_COUNTRY);
const phone = ref("");
const otp = ref("");
const pwd = ref("");
const loading = ref(false);
const cooldown = ref(0);
const showCC = ref(false);

let timer: ReturnType<typeof setInterval> | null = null;
async function sendOtp() {
  if (cooldown.value > 0 || !phone.value) return;
  const r = await api.sendOtp({ countryCode: country.value.dial, phone: phone.value, scene: "reset" });
  uni.showToast({ title: t("auth.otpSent"), icon: "none" });
  cooldown.value = r.cooldown;
  timer = setInterval(() => {
    cooldown.value -= 1;
    if (cooldown.value <= 0 && timer) clearInterval(timer);
  }, 1000);
}
async function submit() {
  loading.value = true;
  try {
    await api.resetPassword({ countryCode: country.value.dial, phone: phone.value, otp: otp.value, password: pwd.value });
    uni.showToast({ title: "OK", icon: "success" });
    setTimeout(() => uni.navigateBack(), 500);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <pb-scaffold :title="$t('auth.forgotTitle')" show-back>
    <view class="px-[40rpx] pt-[24rpx]">
      <text class="pb-h1">{{ $t("auth.forgotTitle") }}</text>

      <view class="mt-[32rpx] flex items-center gap-[16rpx] rounded-[22rpx] bg-faint px-[24rpx]" style="height: 96rpx">
        <view class="flex items-center gap-[8rpx]" @tap="showCC = true">
          <text class="text-[34rpx]">{{ country.flag }}</text>
          <text class="text-[28rpx] font-bold text-ink">{{ country.dial }}</text>
          <pb-icon name="chevron" :size="24" class="text-sub" />
        </view>
        <view class="pb-sep" />
        <input v-model="phone" class="flex-1 text-[30rpx] text-ink" type="number" :placeholder="$t('auth.phone')" />
      </view>

      <view class="mt-[24rpx] flex items-center gap-[16rpx]">
        <view class="flex-1"><pb-otp-input v-model="otp" :length="6" /></view>
        <pb-button size="md" type="tonal" :disabled="cooldown > 0" @click="sendOtp">
          {{ cooldown > 0 ? cooldown + "s" : $t("auth.sendCode") }}
        </pb-button>
      </view>

      <view class="mt-[24rpx]"><pb-field v-model="pwd" :placeholder="$t('auth.newPwd')" password /></view>

      <view class="mt-[40rpx]"><pb-button block size="lg" :loading="loading" @click="submit">{{ $t("auth.resetDone") }}</pb-button></view>
    </view>

    <pb-country-picker v-model:visible="showCC" @select="(c: Country) => (country = c)" />
  </pb-scaffold>
</template>

<style scoped>
.pb-sep { width: 2rpx; height: 44rpx; background: var(--pb-line, rgba(0, 0, 0, 0.08)); }
</style>
