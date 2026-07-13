<script setup lang="ts">
// 修改密码（后端 changePassword 端点待定，MVP 端侧校验 + mock 成功）。
import { ref } from "vue";
const oldPwd = ref("");
const newPwd = ref("");
const loading = ref(false);
function submit() {
  if (!newPwd.value) return;
  loading.value = true;
  setTimeout(() => {
    loading.value = false;
    uni.showToast({ title: "OK", icon: "success" });
    setTimeout(() => uni.navigateBack(), 500);
  }, 500);
}
</script>

<template>
  <pb-scaffold :title="$t('settings.changePwd')" show-back>
    <view class="flex flex-col gap-[24rpx] px-[40rpx] pt-[24rpx]">
      <pb-field v-model="oldPwd" :label="$t('settings.oldPwd')" password />
      <pb-field v-model="newPwd" :label="$t('settings.newPwd')" password />
      <view class="mt-[24rpx]"><pb-button block size="lg" :loading="loading" @click="submit">{{ $t("settings.save") }}</pb-button></view>
    </view>
  </pb-scaffold>
</template>
