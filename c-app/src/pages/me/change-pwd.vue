<script setup lang="ts">
/**
 * 修改密码。走 `POST /mp/user/password`（已登录，cUserNo 由后端取自会话）。
 *
 * 这页此前是 `setTimeout` 假装成功 —— 注释写着「端侧校验 + mock 成功」，
 * 实际上根本没调后端却弹一个 OK，于是「旧口令错」从来没被拒过。
 */
import { ref } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";

const { t } = useI18n();
const oldPwd = ref("");
const newPwd = ref("");
const loading = ref(false);

/** 与后端 CredentialService.MIN_LEN 一致。端上先挡一次只为少跑一趟，判定权在后端。 */
const MIN_LEN = 8;

async function submit() {
  if (!oldPwd.value || !newPwd.value) return;
  if (newPwd.value.trim().length < MIN_LEN) {
    uni.showToast({ title: t("settings.pwdTooShort", { n: MIN_LEN }), icon: "none" });
    return;
  }
  loading.value = true;
  try {
    await api.changePassword({ oldPassword: oldPwd.value, newPassword: newPwd.value });
    uni.showToast({ title: t("settings.pwdChanged"), icon: "success" });
    setTimeout(() => uni.navigateBack(), 500);
  } catch (e) {
    // 如实把后端的拒绝告诉用户（旧口令错 / 太短 / 与原口令相同 / 密码登录未开启）
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
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
