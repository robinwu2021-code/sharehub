<script setup lang="ts">
// 首页「使用中订单」常驻卡（主色实心 + 脉动电量图标）。
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import type { RentOrder } from "@/types";
const props = defineProps<{ order: RentOrder }>();
const emit = defineEmits<{ (e: "click"): void }>();
const { t } = useI18n();
const amt = computed(() => `${t("common.currency")} ${props.order.amount.toFixed(2)}`);
</script>

<template>
  <view class="pb-ong" hover-class="pb-ong--press" :hover-stay-time="40" @tap="emit('click')">
    <view class="pb-ong__pulse"><pb-icon name="battery" :size="40" /></view>
    <view class="pb-ong__mid">
      <text class="pb-ong__t">{{ $t("orders.ongoing") }} · {{ order.siteNameBorrow }}</text>
      <text class="pb-ong__s">{{ order.durationMin }} {{ $t("common.min") }}</text>
    </view>
    <text class="pb-ong__amt pb-num">{{ amt }}</text>
    <pb-icon name="chevron" :size="28" />
  </view>
</template>

<style scoped>
.pb-ong {
  display: flex;
  align-items: center;
  gap: 18rpx;
  background: var(--pb-primary);
  color: var(--pb-on-primary);
  border-radius: 26rpx;
  padding: 24rpx;
  box-shadow: 0 12rpx 30rpx var(--pb-primary-tint);
  transition: transform 0.12s ease;
}
.pb-ong--press { transform: scale(0.985); }
.pb-ong__pulse { width: 76rpx; height: 76rpx; border-radius: 9999px; background: rgba(255, 255, 255, 0.16); display: flex; align-items: center; justify-content: center; animation: pbpulse 1.6s ease-in-out infinite; }
@keyframes pbpulse { 0%, 100% { transform: scale(1); } 50% { transform: scale(1.09); } }
.pb-ong__mid { flex: 1; }
.pb-ong__t { display: block; font-size: 28rpx; font-weight: 800; }
.pb-ong__s { display: block; font-size: 24rpx; opacity: 0.85; margin-top: 4rpx; }
.pb-ong__amt { font-size: 32rpx; font-weight: 800; }
</style>
