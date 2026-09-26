<script setup lang="ts">
import { ref, computed, watch } from "vue";
import { onShow } from "@dcloudio/uni-app";
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

/**
 * 可用登录方式由**后端下发**（`GET /mp/auth/methods`，需求 OPS-SYS-07 按国家可配）。
 *
 * 此前这里是写死的两个页签，而后端连 `password` 这个 grantType 都没有 ——
 * 用户切到「密码」点登录，吃一个 400。现在开关关着时那个页签根本不出现。
 *
 * 初值故意只留 otp：拿到下发之前**宁可少画一个页签**，也不要先画出来再收回去
 * （那会让用户正好点在消失的那一下）。
 */
const methods = ref({ otp: true, password: false });
const modes = computed(() => {
  const m = [];
  if (methods.value.otp) m.push({ label: t("auth.otp"), value: "otp" });
  if (methods.value.password) m.push({ label: t("auth.password"), value: "password" });
  return m;
});

async function loadMethods() {
  try {
    const r = await api.loginMethods(country.value.iso);
    methods.value = { otp: r.otp, password: r.password };
    // 当前选中的方式被关掉了 → 落回第一个可用的，否则表单会停在一个提交必失败的页签上
    if (!modes.value.some((o) => o.value === mode.value) && modes.value.length) {
      mode.value = modes.value[0].value;
    }
  } catch {
    // 取不到就维持初值（只有 OTP）。这里**不静默放开密码** ——
    // 放开的后果是用户撞 400，而收着的后果只是少一个入口。
  }
}
/*
 * 用 onShow 而不是 onMounted：uni 的页面栈会**缓存页面实例**，从别的页返回登录页时
 * onMounted 不会重跑（本地实测过：改了下发值、返回登录页，页签还是旧的）。
 * 于是运营台拨了开关之后，还开着 App 的用户会看到一个后端必然拒绝的页签 ——
 * 正是这次要消掉的那种死路。仓库里 home/me/notice 已经都是 onShow。
 */
onShow(loadMethods);
// 换国家要重取：开关是按国家配的
watch(() => country.value.iso, loadMethods);

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
        ? {
            grantType: "phone_otp" as const,
            countryCode: country.value.dial,
            country: country.value.iso,
            phone: phone.value,
            otp: otp.value,
          }
        : {
            grantType: "password" as const,
            countryCode: country.value.dial,
            country: country.value.iso,
            phone: phone.value,
            password: pwd.value,
          };
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

      <!-- 只剩一种方式时不画分段控件：一个页签的分段控件看起来像坏了 -->
      <view v-if="modes.length > 1" class="mt-[32rpx]">
        <pb-segmented v-model="mode" :options="modes" />
      </view>

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

      <!-- 密码登录没开时「忘记密码」也不该出现：后端 password/reset 会 400，点进去是条死路 -->
      <view v-if="methods.password" class="mt-[20rpx] flex justify-end px-[4rpx]" @tap="goForgot">
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
