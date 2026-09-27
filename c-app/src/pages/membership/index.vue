<script setup lang="ts">
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { t } from "@/i18n";
import type { MembershipPlan } from "@/types";

const list = ref<MembershipPlan[]>([]);
const loading = ref(true);

async function load() {
  loading.value = true;
  try {
    list.value = await api.listMemberships();
  } finally {
    loading.value = false;
  }
}
onShow(load);

/**
 * 把结构化权益渲染成**本地化文案**。
 *
 * 后端给的是 `rights: { rentDiscount, freeMinutes, depositFree }`，不是文案数组 ——
 * 让后端返文案，阿语界面上就会出现一句中文（`FeeItem` 那边已经为同一个错改过一轮），
 * 而且自由文本里解析不出折扣率，于是页面写着「租金九折」而账单照收原价。
 *
 * `rentDiscount` 是**折扣率**（0.9），而「折扣」在中英文里是**互补的两个数**：
 * 中文说「九折」（rate×10 = 9），英文说「10% off」（(1−rate)×100 = 10）。
 * 所以两个值都传，各语言的文案挑自己要的那个 —— 只传一个，
 * 英文就会变成 "9% off rental"，把九折说成了九一折，而这不会有任何地方报错。
 */
function benefitsOf(m: MembershipPlan): string[] {
  const r = m.rights;
  if (!r) return [];
  const out: string[] = [];
  if (r.rentDiscount != null && r.rentDiscount < 1) {
    out.push(
      t("member.rightDiscount", {
        v: Number((r.rentDiscount * 10).toFixed(1)),
        off: Number(((1 - r.rentDiscount) * 100).toFixed(1)),
      }),
    );
  }
  if (r.freeMinutes) out.push(t("member.rightFreeMinutes", { n: r.freeMinutes }));
  if (r.depositFree) out.push(t("member.rightDepositFree"));
  return out;
}

/**
 * 购买入口尚未接通 —— 这里**如实说出来**。
 *
 * 原先它弹的是套餐名（`m.name`）：按钮写着「立即购买」，点下去冒出一个套餐名，
 * 读起来像下单成功了。同一个仓库里 `profile.vue` 的 `bindPhone` 早就是
 * 「即将上线」的写法，这里跟着它走。
 *
 * **为什么还不能买**（2026-09-27 更新）：权益已经接进计价链了
 * （`MemberRightsPort` → `ChargeChain`，见 TDD-会员套餐），所以原来那个理由没了。
 * 现在的阻塞是**没有任何页面在收钱** —— 全站没有一处调 `/mp/trade/pay`、
 * `pay_order` 零行，「收款动作」的口径还没拍板。开了购买等于记了单没收钱，
 * 那比「即将上线」糟得多。
 */
function buy(_m: MembershipPlan) {
  uni.showToast({ title: t("member.buySoon"), icon: "none" });
}
</script>

<template>
  <pb-scaffold :title="$t('me.membership')" show-back>
    <view class="flex flex-col gap-[24rpx] px-[32rpx] pt-[24rpx]">
      <pb-card v-for="(m, i) in list" :key="m.planNo" class="pb-rise" :style="{ animationDelay: i * 60 + 'ms' }">
        <view class="flex items-center justify-between">
          <text class="pb-h2">{{ m.name }}</text>
          <pb-tag v-if="m.active" type="success">{{ $t("member.active") }}</pb-tag>
        </view>
        <view class="mt-[14rpx] flex items-baseline gap-[6rpx]">
          <pb-amount :value="m.price" size="lg" />
          <!-- 按 cardType 判，不按方案号里有没有 "MONTH" —— 号是取号器给的（MP0001），里面本来就没有卡型 -->
          <text v-if="m.cardType === 'MONTHLY'" class="text-[24rpx] text-sub">{{ $t("member.perMonth") }}</text>
        </view>
        <view class="mt-[24rpx] flex flex-col gap-[14rpx]">
          <view v-for="(b, j) in benefitsOf(m)" :key="j" class="flex items-center gap-[14rpx]">
            <view class="pb-mb-check"><pb-icon name="check" :size="22" :stroke="3" /></view>
            <text class="text-[26rpx] text-ink">{{ b }}</text>
          </view>
        </view>
        <view v-if="m.active && m.expireAt" class="mt-[18rpx] text-[22rpx] text-sub">
          {{ $t("member.validTo") }} {{ m.expireAt }}
        </view>
        <view class="mt-[28rpx]">
          <pb-button block :type="m.active ? 'tonal' : 'primary'" @click="buy(m)">
            {{ m.active ? $t("member.active") : $t("member.buy") }}
          </pb-button>
        </view>
      </pb-card>
      <pb-empty v-if="!loading && !list.length" icon="card" :text="$t('member.none')" />
    </view>
  </pb-scaffold>
</template>

<style scoped>
.pb-mb-check {
  width: 38rpx;
  height: 38rpx;
  border-radius: 9999px;
  background: var(--pb-primary-tint);
  color: var(--pb-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
</style>
