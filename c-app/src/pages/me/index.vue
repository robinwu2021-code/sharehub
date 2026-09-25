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
async function logout() {
  await userStore.logout();
  wallet.value = null;
}
</script>

<template>
  <pb-scaffold tab="me">
    <view class="px-[32rpx] pt-[36rpx]">
      <view class="flex items-center justify-between">
        <text class="pb-h1">{{ $t("me.title") }}</text>
        <view class="pb-icon-btn" hover-class="pb-icon-btn--press" :hover-stay-time="40" @tap="sheet = true">
          <pb-icon name="sparkles" :size="38" />
        </view>
      </view>

      <!-- 资料 / 游客 -->
      <view class="mt-[24rpx]">
        <pb-card v-if="isLoggedIn && profile" @tap="go('/pages/me/profile')">
          <view class="flex items-center gap-[24rpx]">
            <pb-avatar :size="112" />
            <view class="flex-1">
              <text class="text-[34rpx] font-bold text-ink">{{ profile.nickname }}</text>
              <view class="mt-[6rpx] text-[24rpx] text-sub">{{ profile.phone }}</view>
            </view>
            <pb-tag v-if="profile.freeDeposit" type="success">{{ $t("me.freeDeposit") }}</pb-tag>
            <pb-icon name="chevron" :size="30" class="text-sub" />
          </view>
        </pb-card>
        <pb-card v-else>
          <view class="flex flex-col items-center gap-[20rpx] py-[24rpx]">
            <text class="text-[30rpx] text-sub">{{ $t("me.loginHint") }}</text>
            <pb-button size="lg" @click="goLogin">{{ $t("common.login") }}</pb-button>
          </view>
        </pb-card>
      </view>

      <!-- 钱包 / 信用 -->
      <view v-if="isLoggedIn && wallet" class="mt-[20rpx]">
        <pb-card @tap="go('/pages/wallet/index')">
          <view class="flex">
            <pb-stat :label="$t('me.wallet')"><pb-amount :value="wallet.balance" size="md" /></pb-stat>
            <pb-stat :label="$t('me.freeDeposit')"><pb-amount :value="wallet.frozen" size="md" /></pb-stat>
            <pb-stat :label="$t('me.credit')">
              <text v-if="profile" class="text-primary">{{ profile.creditScore }}</text>
            </pb-stat>
          </view>
        </pb-card>
      </view>

      <!-- 菜单 -->
      <view class="mt-[20rpx]">
        <pb-card :pad="false">
          <view class="px-[28rpx]">
            <pb-cell icon="wallet" :title="$t('me.wallet')" is-link @click="go('/pages/wallet/index')" />
            <pb-cell icon="heart" :title="$t('me.favorites')" is-link @click="go('/pages/me/favorites')" />
            <pb-cell icon="ticket" :title="$t('me.coupons')" is-link @click="go('/pages/coupons/index')" />
            <pb-cell icon="card" :title="$t('me.membership')" is-link @click="go('/pages/membership/index')" />
            <pb-cell icon="receipt" :title="$t('reports.title')" is-link @click="go('/pages/reports/index')" />
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
.pb-icon-btn {
  width: 76rpx;
  height: 76rpx;
  border-radius: 9999px;
  background: var(--pb-faint);
  color: var(--pb-ink);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.12s ease;
}
.pb-icon-btn--press { transform: scale(0.92); }
</style>
