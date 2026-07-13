<script setup lang="ts">
import { ref, computed } from "vue";
import { useI18n } from "vue-i18n";
import { useUserStore } from "@/stores/user";
import { useAppStore } from "@/stores/app";
import { api } from "@/api";
import { DEFAULT_COUNTRY } from "@/shared/countries";
import type { Country } from "@/types";

const { t } = useI18n();
const userStore = useUserStore();
const appStore = useAppStore();

const mode = ref("otp"); // otp | password
const country = ref<Country>(DEFAULT_COUNTRY);
const phone = ref("");
const otp = ref("");
const pwd = ref("");
const loading = ref(false);
const cooldown = ref(0);
const showCC = ref(false);

const modes = computed(() => [
  { label: t("auth.otp"), value: "otp" },
  { label: t("auth.password"), value: "password" },
]);

let timer: ReturnType<typeof setInterval> | null = null;
async function sendOtp() {
  if (cooldown.value > 0 || !phone.value) return;
  const r = await api.sendOtp({ countryCode: country.value.dial, phone: phone.value, scene: "login" });
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
    const params =
      mode.value === "otp"
        ? { grantType: "phone_otp" as const, countryCode: country.value.dial, phone: phone.value, otp: otp.value }
        : { grantType: "password" as const, countryCode: country.value.dial, phone: phone.value, password: pwd.value };
    await userStore.login(params);
    uni.showToast({ title: "OK", icon: "success" });
    setTimeout(() => uni.navigateBack(), 400);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
}
function cycleLang() {
  const order = ["zh", "en", "ar"] as const;
  appStore.setLang(order[(order.indexOf(appStore.lang) + 1) % 3]);
}
function goForgot() {
  uni.navigateTo({ url: "/pages/auth/forgot" });
}
function goRegister() {
  uni.navigateTo({ url: "/pages/auth/register" });
}
</script>

<template>
  <pb-scaffold :title="$t('login.title')" show-back>
    <template #appbar-right>
      <view class="pb-lang" @tap="cycleLang"><pb-icon name="globe" :size="34" /></view>
    </template>

    <view class="px-[40rpx] pt-[24rpx]">
      <text class="pb-h1">{{ $t("login.title") }}</text>

      <view class="mt-[32rpx]"><pb-segmented v-model="mode" :options="modes" /></view>

      <!-- 区号 + 手机号 -->
      <view class="mt-[32rpx] flex items-center gap-[16rpx] rounded-[22rpx] bg-faint px-[24rpx]" style="height: 96rpx">
        <view class="flex items-center gap-[8rpx]" @tap="showCC = true">
          <text class="text-[34rpx]">{{ country.flag }}</text>
          <text class="text-[28rpx] font-bold text-ink">{{ country.dial }}</text>
          <pb-icon name="chevron" :size="24" class="text-sub" />
        </view>
        <view class="pb-sep" />
        <input v-model="phone" class="flex-1 text-[30rpx] text-ink" type="number" :placeholder="$t('auth.phone')" />
      </view>

      <!-- OTP 或 密码 -->
      <view v-if="mode === 'otp'" class="mt-[24rpx] flex items-center gap-[16rpx]">
        <view class="flex-1"><pb-otp-input v-model="otp" :length="6" /></view>
        <pb-button size="md" type="tonal" :disabled="cooldown > 0" @click="sendOtp">
          {{ cooldown > 0 ? cooldown + "s" : $t("auth.sendCode") }}
        </pb-button>
      </view>
      <view v-else class="mt-[24rpx]">
        <pb-field v-model="pwd" :placeholder="$t('auth.pwd')" password />
      </view>

      <view class="mt-[20rpx] flex justify-end px-[4rpx]" @tap="goForgot">
        <text class="text-[24rpx] text-primary">{{ $t("auth.forgot") }}</text>
      </view>

      <view class="mt-[36rpx]"><pb-button block size="lg" :loading="loading" @click="submit">{{ $t("login.submit") }}</pb-button></view>

      <view class="mt-[28rpx] flex items-center justify-center gap-[8rpx]">
        <text class="text-[24rpx] text-sub">{{ $t("auth.noAccount") }}</text>
        <text class="text-[24rpx] font-bold text-primary" @tap="goRegister">{{ $t("auth.register") }}</text>
      </view>
      <text class="mt-[24rpx] block text-center text-[22rpx] text-sub">{{ $t("login.agree") }}</text>
    </view>

    <pb-country-picker v-model:visible="showCC" @select="(c: Country) => (country = c)" />
  </pb-scaffold>
</template>

<style scoped>
.pb-lang { width: 68rpx; height: 68rpx; border-radius: 9999px; background: var(--pb-faint); color: var(--pb-ink); display: flex; align-items: center; justify-content: center; }
.pb-sep { width: 2rpx; height: 44rpx; background: var(--pb-line, rgba(0, 0, 0, 0.08)); }
</style>
