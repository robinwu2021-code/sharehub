<script setup lang="ts">
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import type { Coupon } from "@/types";
import { money } from "@/shared/format";

const list = ref<Coupon[]>([]);
const loading = ref(true);

const tagType: Record<Coupon["status"], "success" | "neutral"> = {
  UNUSED: "success",
  USED: "neutral",
  EXPIRED: "neutral",
};
const statusKey: Record<Coupon["status"], string> = {
  UNUSED: "coupon.unused",
  USED: "coupon.used",
  EXPIRED: "coupon.expired",
};

async function load() {
  loading.value = true;
  try {
    list.value = await api.listCoupons();
  } finally {
    loading.value = false;
  }
}
onShow(load);
</script>

<template>
  <pb-scaffold :title="$t('me.coupons')" show-back>
    <view class="flex flex-col gap-[20rpx] px-[32rpx] pt-[24rpx]">
      <pb-card
        v-for="(c, i) in list"
        :key="c.couponNo"
        :flat="c.status !== 'UNUSED'"
        class="pb-rise"
        :style="{ animationDelay: i * 50 + 'ms' }"
      >
        <view class="flex items-center" :class="{ 'is-dim': c.status !== 'UNUSED' }">
          <view class="pb-cpn" :class="{ 'is-live': c.status === 'UNUSED' }">
            <view class="flex items-baseline">
              <text class="text-[24rpx] font-bold">{{ $t("common.currency") }}</text>
              <text class="pb-num ms-[4rpx] text-[60rpx] font-extrabold leading-none">{{ c.amount }}</text>
            </view>
            <text class="mt-[2rpx] text-[20rpx] font-bold">{{ $t("coupon.off") }}</text>
          </view>
          <view class="ms-[24rpx] flex-1">
            <text class="text-[30rpx] font-bold text-ink">{{ c.title }}</text>
            <view class="mt-[8rpx] text-[22rpx] text-sub">
              {{ c.threshold > 0 ? $t("coupon.min") + " " + money(c.threshold) : $t("coupon.noMin") }}
            </view>
            <view class="mt-[4rpx] text-[22rpx] text-sub">{{ $t("coupon.validTo") }} {{ c.expireAt }}</view>
          </view>
          <pb-tag :type="tagType[c.status]">{{ $t(statusKey[c.status]) }}</pb-tag>
        </view>
      </pb-card>
      <pb-empty v-if="!loading && !list.length" icon="ticket" :text="$t('coupon.none')" />
    </view>
  </pb-scaffold>
</template>

<style scoped>
.pb-cpn {
  min-width: 156rpx;
  padding: 22rpx 20rpx;
  border-radius: 22rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: var(--pb-faint);
  color: var(--pb-sub);
}
.pb-cpn.is-live { background: var(--pb-primary-tint); color: var(--pb-primary); }
.is-dim { opacity: 0.5; }
</style>
