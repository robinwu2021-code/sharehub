<script setup lang="ts">
import { ref, onMounted } from "vue";
import { useUserStore } from "@/stores/user";
import { api } from "@/api";
const userStore = useUserStore();
const showLogout = ref(false);
/**
 * 「修改密码」要按后端下发的开关显隐（需求 OPS-SYS-07）。
 * 密码登录没开时后端 `password/change` 会 400 —— 入口留着就是条死路。
 * 初值 false：拿到下发之前宁可不画，也不要先画出来再收回去。
 */
const passwordOn = ref(false);
onMounted(async () => {
  try {
    passwordOn.value = (await api.loginMethods()).password;
  } catch {
    // 取不到就维持 false —— 少一个入口比撞 400 好
  }
});
function go(url: string) {
  uni.navigateTo({ url });
}
function logout() {
  userStore.logout();
  uni.reLaunch({ url: "/pages/home/index" });
}
</script>

<template>
  <pb-scaffold :title="$t('settings.title')" show-back>
    <view class="flex flex-col gap-[20rpx] px-[32rpx] pt-[24rpx]">
      <pb-card :pad="false">
        <view class="px-[28rpx]">
          <pb-cell
            v-if="passwordOn"
            icon="lock"
            :title="$t('settings.changePwd')"
            is-link
            @click="go('/pages/me/change-pwd')"
          />
          <pb-cell icon="trash" :title="$t('settings.deleteAccount')" is-link @click="go('/pages/me/delete-account')" />
        </view>
      </pb-card>
      <pb-card :pad="false">
        <view class="px-[28rpx]">
          <pb-cell icon="info" :title="$t('settings.about')" is-link @click="go('/pages/me/about')" />
          <pb-cell icon="shield" :title="$t('settings.privacy')" is-link @click="go('/pages/me/policy')" />
        </view>
      </pb-card>
      <view class="mt-[12rpx]"><pb-button block type="tonal" @click="showLogout = true">{{ $t("settings.logout") }}</pb-button></view>
      <text class="text-center text-[22rpx] text-sub">{{ $t("settings.version") }} 1.0.0</text>
    </view>
    <pb-dialog
      v-model:visible="showLogout"
      :title="$t('settings.logout')"
      :confirm-text="$t('settings.logout')"
      @confirm="logout"
    />
  </pb-scaffold>
</template>
