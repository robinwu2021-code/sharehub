<script setup lang="ts">
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { storeToRefs } from "pinia";
import { useUserStore } from "@/stores/user";
import { api } from "@/api";
import { ensureLoggedIn } from "@/shared/auth-gate";
import { phoneText } from "@/shared/format";
import type { WalletOverview } from "@/types";
import { useDoubleBackExit } from "@/shared/exit";

useDoubleBackExit();

const userStore = useUserStore();
const { profile, isLoggedIn } = storeToRefs(userStore);
const wallet = ref<WalletOverview | null>(null);
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
/**
 * 需要登录的入口（钱包 / 收藏 / 券包 / 会员 / 报障 / 资料）。游客点它们先去登录，登录完回到目标页。
 *
 * 此前不设门禁，靠会话守卫在 401 时把人送去登录页；而守卫现在只处理「原本有会话、失效了」
 * （游客撞 401 不再被 reLaunch —— 否则游客开 App 首页就被踢走）。于是游客点「钱包」，
 * 两个接口 401，页面显示「暂无交易记录」：读起来是「你的钱包是空的」，而不是「请先登录」
 * （Android 模拟器实测）。门禁放在入口，比让页面各自猜 401 的含义可靠。
 * 设置页不在此列：关于、隐私政策游客也该能看。
 */
const NEEDS_LOGIN = ["/pages/wallet/", "/pages/me/favorites", "/pages/coupons/", "/pages/membership/", "/pages/reports/", "/pages/me/profile"];

function go(url: string) {
  if (NEEDS_LOGIN.some((p) => url.startsWith(p)) && !ensureLoggedIn(url)) return;
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
        <!-- 小程序右上角是微信胶囊按钮，这个按钮会被盖住；同一入口在下面列表里（Appearance），那端就不出 -->
        <!-- #ifndef MP -->
        <view class="pb-icon-btn" hover-class="pb-icon-btn--press" :hover-stay-time="40" @tap="sheet = true">
          <pb-icon name="sparkles" :size="38" />
        </view>
        <!-- #endif -->
      </view>

      <!-- 资料 / 游客 -->
      <view class="mt-[24rpx]">
        <pb-card v-if="isLoggedIn && profile" @tap="go('/pages/me/profile')">
          <view class="flex items-center gap-[24rpx]">
            <pb-avatar :size="112" />
            <view class="flex-1">
              <!--
                昵称 / 手机号都可能是空的：验证码建户不收昵称，而手机号明文**没有存放处**
                （pb_pii 未建，后端如实出空，见 ConsumerProfilePhoneTest）。此前两行照样渲染，
                资料卡上只剩一个头像 —— 看起来像数据没加载出来（Android 模拟器实测）。
                没昵称就引导去填（点卡片即进资料页）；没手机号就不画那一行，不编一个出来。
              -->
              <text class="text-[34rpx] font-bold" :class="profile.nickname ? 'text-ink' : 'text-sub'">
                {{ profile.nickname || $t("profile.nicknamePh") }}
              </text>
              <view v-if="profile.phone" class="mt-[6rpx] text-[24rpx] text-sub">{{ phoneText(profile.phone) }}</view>
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
            <pb-stat :label="$t('me.freeDeposit')"><pb-amount :value="wallet.frozenAmount" size="md" /></pb-stat>
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
