<script setup lang="ts">
// 订单详情：状态头 + 借还网点 + 费用明细 + 状态时间线 + 报障入口。
import { ref, computed, onUnmounted } from "vue";
import { onLoad, onHide, onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import type { ConsumerOrder, OrderStatus } from "@/types";
import { timeOf, elapsedMin, durationText } from "@/shared/format";
import { ORDER_REFRESH_MS } from "@/shared/constants";

const order = ref<ConsumerOrder | null>(null);
const buying = ref(false);
/**
 * 秒针。进行中订单的「已用时长」**在端上按 rentStartAt 现算** ——
 * 后端的 `durationMin` 只在归还那一刻写入，进行中的单它恒为 0，
 * 于是这一页此前是一个**不动的 0 分钟**，用户唯一的判断方式是退出重进。
 *
 * **只算时长，不算钱**：计费口径（封顶/免费时长/权益/券）全在服务端，
 * 端上乘一个单价得到的是一个「差一点」的数字，而用户会拿它去对账单。
 */
const tick = ref(Date.now());
let tickTimer: ReturnType<typeof setInterval> | null = null;
let refreshTimer: ReturnType<typeof setInterval> | null = null;
const isInUse = computed(() => order.value?.status === "IN_USE" || order.value?.status === "DISPENSING");
/** 进行中按 rentStartAt 现算，已结束用服务端写好的 durationMin（那是结算依据）。 */
const liveMin = computed(() => {
  if (!order.value) return null;
  if (!isInUse.value) return order.value.durationMin;
  // eslint-disable-next-line @typescript-eslint/no-unused-expressions
  tick.value; // 依赖秒针，驱动重算
  return elapsedMin(order.value.rentStartAt);
});
const tagType: Record<OrderStatus, "primary" | "success" | "warning" | "danger" | "neutral"> = {
  CREATED: "neutral",
  DISPENSING: "warning",
  IN_USE: "primary",
  RETURNED: "warning",
  SETTLED: "success",
  CLOSED: "neutral",
  EXCEPTION: "danger",
};

let orderNo = "";
onLoad(async (q) => {
  orderNo = (q?.orderNo as string) || "";
  await load();
});

async function load() {
  order.value = await api.getOrder(orderNo);
  syncTimers();
}

/**
 * 进行中才开表。归还由柜机侧触发（设备事件 → `/internal/trade/.../return`），
 * 端上收不到推送，所以这里定时回查 —— 用户把宝插进柜子之后，这一页要自己变成结算展示，
 * 而不是停在「使用中」等他手动刷新。
 */
function syncTimers() {
  stopTimers();
  if (!isInUse.value) return;
  tickTimer = setInterval(() => (tick.value = Date.now()), 1000);
  refreshTimer = setInterval(load, ORDER_REFRESH_MS);
}
function stopTimers() {
  if (tickTimer) clearInterval(tickTimer);
  if (refreshTimer) clearInterval(refreshTimer);
  tickTimer = refreshTimer = null;
}
// 退到后台就停：看不见的页面继续每秒跑一次定时器，只是在耗电
onHide(stopTimers);
onShow(() => {
  if (order.value) {
    void load();   // 回到前台先补一次：后台期间可能已经归还了
  }
});
onUnmounted(stopTimers);

/** 找可还网点（C-US-03）：只列有空仓的柜 —— 满仓的柜子走到跟前才发现插不进去。 */
function findReturn() {
  uni.reLaunch({ url: "/pages/home/index?returnable=1" });
}

function report() {
  if (order.value) uni.navigateTo({ url: `/pages/order/feedback?orderNo=${order.value.orderNo}` });
}
async function buyout() {
  if (!order.value) return;
  buying.value = true;
  try {
    order.value = await api.buyout(order.value.orderNo);
  } finally {
    buying.value = false;
  }
}
</script>

<template>
  <pb-scaffold :title="$t('orders.detail')" show-back>
    <view v-if="order" class="px-[32rpx] pt-[24rpx]">
      <!-- 状态头 -->
      <pb-card tint="primary">
        <view class="flex items-center justify-between">
          <pb-tag :type="tagType[order.status]">{{ $t("status." + order.status) }}</pb-tag>
          <!-- 进行中显示走字的时长，不显示金额：那时 feeAmount 恒为 0，
               一个大大的「AED 0.00」读起来像「这次免费」 -->
          <text v-if="isInUse && liveMin != null" class="pb-num text-[44rpx] font-extrabold text-ink">{{ durationText(liveMin) }}</text>
          <pb-amount v-else :value="order.feeAmount" size="lg" />
        </view>
        <view class="mt-[10rpx] text-[24rpx] text-sub">{{ order.orderNo }}</view>
        <view v-if="isInUse" class="mt-[16rpx] flex items-center gap-[8rpx] text-[22rpx] text-sub">
          <pb-icon name="info" :size="26" /><text>{{ $t("orders.feeAtReturn") }}</text>
        </view>
        <view v-if="isInUse" class="mt-[8rpx] flex items-center gap-[8rpx] text-[22rpx] text-sub">
          <pb-icon name="info" :size="26" /><text>{{ $t("orders.capNote") }}</text>
        </view>
      </pb-card>

      <!-- 使用中：先给归还指引（高频），买断是次要出口 -->
      <view v-if="isInUse" class="mt-[20rpx]">
        <pb-button block @click="findReturn">{{ $t("orders.findReturn") }}</pb-button>
      </view>
      <view v-if="isInUse" class="mt-[16rpx]">
        <pb-button block type="tonal" :loading="buying" @click="buyout">{{ $t("orders.buyout") }}</pb-button>
      </view>

      <!-- 借 / 还 网点 -->
      <view class="mt-[20rpx]">
        <pb-card :pad="false">
          <view class="px-[28rpx]">
            <pb-cell icon="battery" :title="$t('orders.borrowAt')" :value="order.siteName ?? order.locationName ?? order.cabinetNo" />
            <pb-cell v-if="order.returnCabinetNo" icon="pin" :title="$t('orders.returnAt')" :value="order.returnSiteName ?? order.returnCabinetNo" />
            <pb-cell icon="clock" :title="$t('orders.duration')" :value="liveMin == null ? '—' : `${liveMin} ${$t('common.min')}`" />
          </view>
        </pb-card>
      </view>

      <!-- 费用明细 -->
      <view class="mt-[20rpx]">
        <text class="mb-[12rpx] block text-[24rpx] text-sub">{{ $t("orders.feeDetail") }}</text>
        <pb-card>
          <view class="flex flex-col gap-[16rpx]">
            <view v-for="(f, i) in order.fees" :key="i" class="flex items-center justify-between">
              <text class="text-[26rpx] text-sub">{{ $t("fee." + f.type) }}</text>
              <pb-amount :value="f.amount" size="sm" />
            </view>
            <view class="mt-[8rpx] flex items-center justify-between border-t-0">
              <text class="text-[28rpx] font-semibold text-ink">{{ $t("orders.total") }}</text>
              <pb-amount :value="order.feeAmount" size="md" />
            </view>
            <!--
              押金单独列在合计之下，不混进上面的明细：它可退、也不构成本单应付。
              此前后端把它当成一条费用返回，于是屏幕上「3.00 + 50.00」下面写着「合计 3.00」——
              账加不起来。现在明细逐项相加 = 合计，押金是合计之外的一笔。
            -->
            <view
              v-if="order.depositAmount"
              class="flex items-center justify-between pt-[4rpx]"
            >
              <text class="text-[24rpx] text-sub">{{ $t("orders.depositRefundable") }}</text>
              <pb-amount :value="order.depositAmount" size="sm" />
            </view>
          </view>
        </pb-card>
      </view>

      <!-- 状态时间线（色块圆点，无连线） -->
      <view class="mt-[20rpx]">
        <text class="mb-[12rpx] block text-[24rpx] text-sub">{{ $t("orders.timeline") }}</text>
        <pb-card>
          <view class="flex flex-col gap-[22rpx]">
            <view v-for="(step, i) in order.timeline" :key="i" class="flex items-center gap-[16rpx]">
              <view class="pb-dot" />
              <text class="flex-1 text-[26rpx] text-ink">{{ $t("status." + step.status) }}</text>
              <text class="text-[24rpx] text-sub">{{ timeOf(step.at) }}</text>
            </view>
          </view>
        </pb-card>
      </view>

      <view class="mt-[32rpx] mb-[20rpx]">
        <pb-button block type="tonal" @click="report">{{ $t("orders.report") }}</pb-button>
      </view>
    </view>
  </pb-scaffold>
</template>

<style scoped>
.pb-dot { width: 20rpx; height: 20rpx; border-radius: 9999px; background: var(--pb-primary); }
</style>
