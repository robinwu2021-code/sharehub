<script setup lang="ts">
// 首页「使用中订单」常驻卡（主色实心 + 脉动电量图标）。
import { computed, onUnmounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import type { ConsumerOrder } from "@/types";
import { elapsedMin, durationText } from "@/shared/format";
const props = defineProps<{ order: ConsumerOrder }>();
const emit = defineEmits<{ (e: "click"): void }>();
const { t } = useI18n();

/**
 * 这张卡此前显示 `durationMin` 与 `feeAmount` —— 进行中的单这两个值在服务端**恒为 0**
 * （只在归还那一刻写入）。于是首页最醒目的那张卡长期写着「0 分钟 · AED 0.00」，
 * 读起来像「这次不要钱」。
 *
 * 现在：时长按 rentStartAt 现算（与订单详情同一个口径），金额只在**已有金额时**才显示，
 * 没有就留空 —— 不编一个 0.00 出来。
 */
const tick = ref(Date.now());
const timer = setInterval(() => (tick.value = Date.now()), 30000);
onUnmounted(() => clearInterval(timer));

const lasted = computed(() => {
  // eslint-disable-next-line @typescript-eslint/no-unused-expressions
  tick.value;
  const m = elapsedMin(props.order.rentStartAt) ?? props.order.durationMin;
  return durationText(m);
});
const amt = computed(() => {
  // 进行中用服务端试算；已结束用实付。两者都没有就留空 —— 不编一个 0.00 出来
  const v = props.order.estimatedFee ?? (props.order.feeAmount > 0 ? props.order.feeAmount : null);
  return v == null ? "" : `${t("common.currency")} ${v.toFixed(2)}`;
});
</script>

<template>
  <view class="pb-ong" hover-class="pb-ong--press" :hover-stay-time="40" @tap="emit('click')">
    <view class="pb-ong__pulse"><pb-icon name="battery" :size="40" /></view>
    <view class="pb-ong__mid">
      <text class="pb-ong__t">{{ $t("orders.ongoing") }} · {{ order.siteName ?? order.locationName ?? order.cabinetNo }}</text>
      <text class="pb-ong__s">{{ lasted }}</text>
    </view>
    <text v-if="amt" class="pb-ong__amt pb-num">{{ amt }}</text>
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
