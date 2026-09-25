<script setup lang="ts">
// 收藏门店：列表 + 取消收藏 + 点选进入借出确认。数据源 listFavorites / toggleFavorite。
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import type { NearbyCabinet } from "@/types";
import { distance } from "@/shared/format";

const { t } = useI18n();
const list = ref<NearbyCabinet[]>([]);
const loading = ref(true);

async function load() {
  loading.value = true;
  try {
    list.value = await api.listFavorites();
  } finally {
    loading.value = false;
  }
}
onShow(load);

async function remove(c: NearbyCabinet) {
  await api.toggleFavorite(c.siteNo);
  list.value = list.value.filter((x) => x.siteNo !== c.siteNo);
  uni.showToast({ title: t("favorites.removed"), icon: "none" });
}
function borrow(c: NearbyCabinet) {
  uni.navigateTo({ url: `/pages/order/confirm?cabinetNo=${c.cabinetNo}` });
}
</script>

<template>
  <pb-scaffold :title="$t('favorites.title')" show-back>
    <view class="flex flex-col gap-[24rpx] px-[32rpx] pt-[24rpx]">
      <view
        v-for="(c, i) in list"
        :key="c.cabinetNo"
        class="pb-rise"
        :style="{ animationDelay: i * 50 + 'ms' }"
        @tap="borrow(c)"
      >
        <pb-card>
          <view class="flex items-center">
            <view class="flex-1">
              <text class="pb-h2">{{ c.siteName }}</text>
              <view class="mt-[8rpx] flex items-center gap-[8rpx] text-[24rpx] text-sub">
                <pb-icon name="pin" :size="26" /><text>{{ c.address }}{{ distance(c.distanceM) ? " · " + distance(c.distanceM) : "" }}</text>
              </view>
              <view class="mt-[18rpx] flex gap-[12rpx]">
                <pb-tag :type="c.availableBorrow > 0 ? 'success' : 'neutral'">{{ $t("home.canBorrow") }} {{ c.availableBorrow }}</pb-tag>
                <pb-tag type="primary">{{ $t("home.canReturn") }} {{ c.availableReturn }}</pb-tag>
              </view>
            </view>
            <view class="pb-fav-btn" hover-class="pb-fav-btn--press" :hover-stay-time="40" @tap.stop="remove(c)">
              <pb-icon name="heart" :size="38" />
            </view>
          </view>
        </pb-card>
      </view>
      <pb-empty v-if="!loading && !list.length" icon="heart" :text="$t('favorites.none')" />
    </view>
  </pb-scaffold>
</template>

<style scoped>
.pb-fav-btn {
  width: 76rpx;
  height: 76rpx;
  border-radius: 9999px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--pb-danger);
  background: var(--pb-danger-tint, var(--pb-faint));
  transition: transform 0.12s ease;
}
.pb-fav-btn--press { transform: scale(0.9); }
</style>
