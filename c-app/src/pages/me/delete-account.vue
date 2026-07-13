<script setup lang="ts">
// 注销账户（PDPL 合规）。后端 logoff 端点待定，MVP 走确认 → 登出 → 回首页。
import { ref } from "vue";
import { useUserStore } from "@/stores/user";
const userStore = useUserStore();
const show = ref(false);
function confirmDelete() {
  userStore.logout();
  uni.showToast({ title: "OK", icon: "success" });
  setTimeout(() => uni.reLaunch({ url: "/pages/home/index" }), 600);
}
</script>

<template>
  <pb-scaffold :title="$t('settings.deleteAccount')" show-back>
    <view class="px-[32rpx] pt-[24rpx]">
      <pb-card tint="danger">
        <view class="flex items-start gap-[16rpx]">
          <pb-icon name="info" :size="40" class="text-danger" />
          <text class="flex-1 text-[26rpx] text-ink" style="line-height: 1.7">{{ $t("settings.deleteWarn") }}</text>
        </view>
      </pb-card>
      <view class="mt-[44rpx]"><pb-button block size="lg" type="danger" @click="show = true">{{ $t("settings.deleteConfirm") }}</pb-button></view>
    </view>
    <pb-dialog
      v-model:visible="show"
      :title="$t('settings.deleteConfirm')"
      :message="$t('settings.deleteWarn')"
      :confirm-text="$t('settings.deleteConfirm')"
      danger
      @confirm="confirmDelete"
    />
  </pb-scaffold>
</template>
