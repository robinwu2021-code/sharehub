<script setup lang="ts">
// 个人资料编辑：昵称/邮箱可改（updateProfile），手机号只读（换绑走独立流程，MVP 占位）。
import { ref, computed } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { storeToRefs } from "pinia";
import { useI18n } from "vue-i18n";
import { useUserStore } from "@/stores/user";
import { api } from "@/api";

const { t } = useI18n();
const userStore = useUserStore();
const { profile } = storeToRefs(userStore);

const nickname = ref("");
const email = ref("");
const saving = ref(false);

function sync() {
  nickname.value = profile.value?.nickname ?? "";
  email.value = profile.value?.email ?? "";
}
onShow(async () => {
  if (!profile.value) await userStore.loadProfile();
  sync();
});

const dirty = computed(
  () => nickname.value.trim() !== (profile.value?.nickname ?? "") || email.value.trim() !== (profile.value?.email ?? ""),
);

async function save() {
  if (!dirty.value || !nickname.value.trim()) return;
  saving.value = true;
  try {
    const p = await api.updateProfile({ nickname: nickname.value.trim(), email: email.value.trim() || undefined });
    userStore.setProfile(p);
    uni.showToast({ title: t("profile.saved"), icon: "success" });
    setTimeout(() => uni.navigateBack(), 500);
  } finally {
    saving.value = false;
  }
}

function bindPhone() {
  uni.showToast({ title: t("profile.bindSoon"), icon: "none" });
}
</script>

<template>
  <pb-scaffold :title="$t('profile.title')" show-back>
    <view class="px-[32rpx] pt-[24rpx]">
      <!-- 头像 -->
      <pb-card>
        <view class="flex items-center gap-[24rpx]">
          <pb-avatar :size="120" />
          <view class="flex-1">
            <text class="text-[30rpx] font-bold text-ink">{{ profile?.nickname }}</text>
            <view class="mt-[6rpx] text-[24rpx] text-sub">{{ profile?.cUserNo }}</view>
          </view>
          <pb-tag v-if="profile?.memberLevel" type="primary">{{ profile.memberLevel }}</pb-tag>
        </view>
      </pb-card>

      <!-- 可改字段 -->
      <view class="mt-[20rpx] flex flex-col gap-[24rpx]">
        <pb-field v-model="nickname" :label="$t('profile.nickname')" :placeholder="$t('profile.nicknamePh')" />
        <pb-field v-model="email" :label="$t('profile.email')" :placeholder="$t('profile.emailPh')" type="text" />
      </view>

      <!-- 手机号（只读 + 换绑入口） -->
      <view class="mt-[20rpx]">
        <pb-card :pad="false">
          <view class="px-[28rpx]">
            <pb-cell icon="phone" :title="$t('profile.phone')">
              <view class="flex items-center gap-[16rpx]">
                <text class="text-sub">{{ profile?.phone || "—" }}</text>
                <pb-button size="sm" type="tonal" @click="bindPhone">{{ $t("profile.change") }}</pb-button>
              </view>
            </pb-cell>
          </view>
        </pb-card>
      </view>

      <view class="mt-[36rpx]">
        <pb-button block size="lg" :disabled="!dirty" :loading="saving" @click="save">{{ $t("profile.save") }}</pb-button>
      </view>
    </view>
  </pb-scaffold>
</template>
