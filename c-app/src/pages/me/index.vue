<script setup lang="ts">
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { storeToRefs } from "pinia";
import { useUserStore } from "@/stores/user";
import { api } from "@/api";
import type { Wallet } from "@/types";

const userStore = useUserStore();
const { profile, isLoggedIn } = storeToRefs(userStore);
const wallet = ref<Wallet | null>(null);
const sheet = ref(false);

async function load() {
  if (isLoggedIn.value) {
    if (!profile.value) await userStore.loadProfile();
    wallet.value = await api.getWallet();
  }
}
onShow(load);

function goLogin() {
  uni.navigateTo({ url: "/pages/login/index" });
}
function go(url: string) {
  uni.navigateTo({ url });
}
function logout() {
  userStore.logout();
  wallet.value = null;
}
</script>

<template>
  <pb-scaffold tab="me">
    <view class="px-[32rpx] pt-[36rpx]">
      <!-- 青绿头部：标题 + 资料 + 钱包/信用 -->
      <view class="pb-me-hd">
        <view class="pb-me-hd__glow" />
        <view class="flex items-center justify-between">
          <text class="pb-me-hd__title">{{ $t("me.title") }}</text>
          <view class="pb-me-hd__btn" hover-class="pb-me-hd__btn--press" :hover-stay-time="40" @tap="sheet = true">
            <pb-icon name="sparkles" :size="38" />
          </view>
        </view>

        <!-- 资料 / 游客 -->
        <view v-if="isLoggedIn && profile" class="mt-[28rpx] flex items-center gap-[24rpx]" @tap="go('/pages/me/profile')">
          <pb-avatar :size="112" class="pb-me-avatar" />
          <view class="flex-1">
            <text class="text-[36rpx] font-bold">{{ profile.nickname }}</text>
            <view class="mt-[6rpx] text-[24rpx] opacity-80">{{ profile.phone }}</view>
          </view>
          <view v-if="profile.freeDeposit" class="pb-me-hd__chip">{{ $t("me.freeDeposit") }}</view>
          <pb-icon name="chevron" :size="30" class="opacity-70" />
        </view>
        <view v-else class="mt-[28rpx] flex items-center gap-[24rpx]">
          <pb-avatar :size="112" class="pb-me-avatar" />
          <view class="flex-1">
            <text class="text-[34rpx] font-bold">{{ $t("me.guest") }}</text>
            <view class="mt-[6rpx] text-[24rpx] opacity-80">{{ $t("me.loginHint") }}</view>
          </view>
          <view class="pb-me-hd__login" hover-class="pb-me-hd__login--press" :hover-stay-time="40" @tap="goLogin">{{ $t("common.login") }}</view>
        </view>

        <!-- 钱包 / 信用（半透明面板） -->
        <view v-if="isLoggedIn && wallet" class="pb-me-wallet" @tap="go('/pages/wallet/index')">
          <view class="pb-me-stat">
            <view class="pb-me-stat__v pb-num">{{ $t("common.currency") }} {{ wallet.balance.toFixed(2) }}</view>
            <view class="pb-me-stat__k">{{ $t("me.wallet") }}</view>
          </view>
          <view class="pb-me-stat__div" />
          <view class="pb-me-stat">
            <view class="pb-me-stat__v pb-num">{{ $t("common.currency") }} {{ wallet.frozen.toFixed(2) }}</view>
            <view class="pb-me-stat__k">{{ $t("me.freeDeposit") }}</view>
          </view>
          <view class="pb-me-stat__div" />
          <view class="pb-me-stat">
            <view v-if="profile" class="pb-me-stat__v pb-num">{{ profile.creditScore }}</view>
            <view class="pb-me-stat__k">{{ $t("me.credit") }}</view>
          </view>
        </view>
      </view>

      <!-- 菜单 -->
      <view class="mt-[20rpx]">
        <pb-card :pad="false">
          <view class="px-[28rpx]">
            <pb-cell icon="wallet" :title="$t('me.wallet')" is-link @click="go('/pages/wallet/index')" />
            <pb-cell icon="heart" :title="$t('me.favorites')" is-link @click="go('/pages/me/favorites')" />
            <pb-cell icon="ticket" :title="$t('me.coupons')" is-link @click="go('/pages/coupons/index')" />
            <pb-cell icon="card" :title="$t('me.membership')" is-link @click="go('/pages/membership/index')" />
            <pb-cell icon="sparkles" :title="$t('theme.title')" is-link @click="sheet = true" />
            <pb-cell icon="lock" :title="$t('settings.title')" is-link @click="go('/pages/me/settings')" />
          </view>
        </pb-card>
      </view>

      <view v-if="isLoggedIn" class="mt-[36rpx] text-center text-[28rpx] text-danger" @tap="logout">
        {{ $t("common.logout") }}
      </view>
    </view>

    <pb-theme-sheet v-model:visible="sheet" />
  </pb-scaffold>
</template>

<style scoped>
/* 青绿头部：与首页 hero / 订单票根同一渐变语言 */
.pb-me-hd {
  position: relative;
  overflow: hidden;
  padding: 12rpx 36rpx 32rpx;
  border-radius: 36rpx;
  color: var(--pb-on-primary);
  background: linear-gradient(135deg, var(--pb-primary), color-mix(in srgb, var(--pb-primary) 72%, #000 14%));
  box-shadow: 0 16rpx 40rpx var(--pb-primary-tint);
}
.pb-me-hd__glow {
  position: absolute;
  top: -90rpx; right: -70rpx;
  width: 300rpx; height: 300rpx;
  border-radius: 9999px;
  background: rgba(255, 255, 255, 0.16);
}
.pb-me-hd__title { position: relative; font-size: 40rpx; font-weight: 800; }
.pb-me-hd__btn {
  position: relative;
  width: 76rpx; height: 76rpx;
  border-radius: 9999px;
  background: rgba(255, 255, 255, 0.22);
  color: var(--pb-on-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.12s ease;
}
.pb-me-hd__btn--press { transform: scale(0.92); }
.pb-me-hd__chip {
  padding: 6rpx 20rpx;
  border-radius: 9999px;
  font-size: 22rpx;
  font-weight: 700;
  background: rgba(255, 255, 255, 0.22);
}
.pb-me-hd__login {
  padding: 14rpx 34rpx;
  border-radius: 9999px;
  font-size: 28rpx;
  font-weight: 700;
  color: var(--pb-primary);
  background: var(--pb-surface);
  transition: transform 0.12s ease;
}
.pb-me-hd__login--press { transform: scale(0.94); }
/* 钱包/信用面板：头部内半透明白 */
.pb-me-wallet {
  position: relative;
  margin-top: 28rpx;
  display: flex;
  align-items: center;
  border-radius: 24rpx;
  background: rgba(255, 255, 255, 0.16);
  padding: 26rpx 8rpx;
}
.pb-me-stat { flex: 1; text-align: center; }
.pb-me-stat__v { font-size: 32rpx; font-weight: 800; line-height: 1.1; }
.pb-me-stat__k { margin-top: 8rpx; font-size: 22rpx; opacity: 0.85; }
.pb-me-stat__div { width: 2rpx; height: 44rpx; background: rgba(255, 255, 255, 0.22); }
/* 头像在青绿头部上：改半透明白，避免青底青字看不清 */
:deep(.pb-me-avatar) {
  background: rgba(255, 255, 255, 0.22);
  color: var(--pb-on-primary);
}
</style>
