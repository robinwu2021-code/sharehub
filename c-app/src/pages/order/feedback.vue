<script setup lang="ts">
import { ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "@/api";
import { t } from "@/i18n";

const types = ["notEjected", "cannotReturn", "overCharged", "other"];
const type = ref("notEjected");
const desc = ref("");
const photos = ref<string[]>([]);
const loading = ref(false);
let orderNo = "";
onLoad((q) => {
  orderNo = (q?.orderNo as string) || "";
});

function addPhoto() {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  uni.chooseImage({ count: 3, success: (r: any) => (photos.value = [...photos.value, ...r.tempFilePaths].slice(0, 3)) });
}
function removePhoto(i: number) {
  photos.value.splice(i, 1);
}
async function submit() {
  loading.value = true;
  try {
    await api.report({ orderNo, type: type.value, desc: desc.value });
    uni.showToast({ title: t("feedback.submitted"), icon: "success" });
    setTimeout(() => uni.navigateBack(), 500);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <pb-scaffold :title="$t('feedback.title')" show-back>
    <view class="px-[32rpx] pt-[24rpx]">
      <text class="text-[24rpx] text-sub">{{ $t("feedback.typeLabel") }}</text>
      <view class="mt-[16rpx] flex flex-wrap gap-[16rpx]">
        <view v-for="ty in types" :key="ty" class="pb-chip" :class="{ 'is-on': type === ty }" @tap="type = ty">
          {{ $t("feedback." + ty) }}
        </view>
      </view>

      <text class="mt-[32rpx] block text-[24rpx] text-sub">{{ $t("feedback.descLabel") }}</text>
      <textarea v-model="desc" class="pb-ta mt-[16rpx]" :placeholder="$t('feedback.descPh')" />

      <view class="mt-[24rpx] flex flex-wrap gap-[16rpx]">
        <view v-for="(p, i) in photos" :key="i" class="pb-photo" @tap="removePhoto(i)">
          <image :src="p" class="pb-photo__img" mode="aspectFill" />
        </view>
        <view v-if="photos.length < 3" class="pb-photo pb-photo--add" @tap="addPhoto"><pb-icon name="camera" :size="44" /></view>
      </view>

      <view class="mt-[40rpx]"><pb-button block size="lg" :loading="loading" @click="submit">{{ $t("feedback.submit") }}</pb-button></view>
    </view>
  </pb-scaffold>
</template>

<style scoped>
.pb-chip { padding: 14rpx 28rpx; border-radius: 9999px; background: var(--pb-faint); color: var(--pb-sub); font-size: 26rpx; font-weight: 600; }
.pb-chip.is-on { background: var(--pb-primary-tint); color: var(--pb-primary); }
.pb-ta { display: block; width: 100%; height: 220rpx; background: var(--pb-faint); border-radius: 22rpx; padding: 24rpx; font-size: 28rpx; color: var(--pb-ink); box-sizing: border-box; }
.pb-photo { width: 150rpx; height: 150rpx; border-radius: 20rpx; overflow: hidden; background: var(--pb-faint); display: flex; align-items: center; justify-content: center; color: var(--pb-sub); }
.pb-photo__img { width: 100%; height: 100%; }
.pb-photo--add { border: 2rpx dashed var(--pb-sub); }
</style>
