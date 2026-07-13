<script setup lang="ts">
import { ref, computed } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useAppStore } from "@/stores/app";
import { api } from "@/api";
import type { Notice } from "@/types";

const app = useAppStore();
const list = ref<Notice[]>([]);
const loading = ref(true);
const titles: Record<string, string> = { zh: "公告", en: "Notice", ar: "الإشعارات" };
const title = computed(() => titles[app.lang] ?? titles.en);

async function load() {
  loading.value = true;
  try {
    list.value = await api.listNotices();
  } finally {
    loading.value = false;
  }
}
onShow(load);
</script>

<template>
  <pb-scaffold :title="title" show-back>
    <view class="flex flex-col gap-[20rpx] px-[32rpx] pt-[24rpx]">
      <pb-card v-for="(n, i) in list" :key="n.noticeNo" class="pb-rise" :style="{ animationDelay: i * 50 + 'ms' }">
        <view class="flex items-center justify-between">
          <text class="text-[30rpx] font-bold text-ink">{{ n.title }}</text>
          <view v-if="!n.read" class="pb-dot-red" />
        </view>
        <text class="mt-[12rpx] block text-[26rpx] text-sub" style="line-height: 1.6">{{ n.body }}</text>
        <text class="mt-[14rpx] block text-[22rpx] text-sub">{{ n.date }}</text>
      </pb-card>
      <pb-empty v-if="!loading && !list.length" icon="bell" :text="title" />
    </view>
  </pb-scaffold>
</template>

<style scoped>
.pb-dot-red { width: 16rpx; height: 16rpx; border-radius: 9999px; background: var(--pb-danger); }
</style>
