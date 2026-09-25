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
 * 购买入口尚未接通 —— 这里**如实说出来**。
 *
 * 原先它弹的是套餐名（`m.name`）：按钮写着「立即购买」，点下去冒出一个套餐名，
 * 读起来像下单成功了。同一个仓库里 `profile.vue` 的 `bindPhone` 早就是
 * 「即将上线」的写法，这里跟着它走。
 *
 * **为什么不干脆把购买做通**：`mbr_plan.rights`（免费时长 / 折扣率 / 免押提额）
 * 一处都没接进计价链（`trade` 里 grep 不到任何 Membership），
 * 现在能买等于收了钱不给权益 —— 那比「即将上线」糟得多。
 * 顺序是先接权益计费（C-MB-02），再开购买（C-MB-01）。
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
          <text v-if="m.planNo.includes('MONTH')" class="text-[24rpx] text-sub">{{ $t("member.perMonth") }}</text>
        </view>
        <view class="mt-[24rpx] flex flex-col gap-[14rpx]">
          <view v-for="(b, j) in m.benefits" :key="j" class="flex items-center gap-[14rpx]">
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
