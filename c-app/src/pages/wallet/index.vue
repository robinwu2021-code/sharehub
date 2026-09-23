<script setup lang="ts">
// 钱包：余额/赠金/押金/免押冻结 概览 + 充值入口（pay recharge）+ 交易流水。
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import type { Wallet, WalletTxn } from "@/types";
import { money, dateTimeOf } from "@/shared/format";

const { t } = useI18n();
const wallet = ref<Wallet | null>(null);
const txns = ref<WalletTxn[]>([]);
const loading = ref(true);
const recharging = ref(false);

async function load() {
  loading.value = true;
  try {
    const [w, page] = await Promise.all([api.getWallet(), api.walletTxns()]);
    wallet.value = w;
    txns.value = page.list;
  } finally {
    loading.value = false;
  }
}
onShow(load);

const txnIcon: Record<WalletTxn["type"], string> = {
  RECHARGE: "plus",
  SPEND: "zap",
  REFUND: "navigation",
  BONUS: "sparkles",
};

async function recharge() {
  recharging.value = true;
  try {
    await api.pay({ scene: "recharge", amount: 20 });
    uni.showToast({ title: t("common.done"), icon: "success" });
    await load();
  } finally {
    recharging.value = false;
  }
}
</script>

<template>
  <pb-scaffold :title="$t('wallet.title')" show-back>
    <view class="px-[32rpx] pt-[24rpx]">
      <!-- 余额主卡 -->
      <pb-card v-if="wallet">
        <view class="flex items-end justify-between">
          <view>
            <text class="text-[24rpx] text-sub">{{ $t("wallet.balance") }}</text>
            <view class="mt-[8rpx]"><pb-amount :value="wallet.balance" size="lg" /></view>
          </view>
          <pb-button size="sm" :loading="recharging" @click="recharge">{{ $t("wallet.recharge") }}</pb-button>
        </view>
        <view class="mt-[28rpx] flex">
          <pb-stat :label="$t('wallet.bonus')"><pb-amount :value="wallet.bonus" size="md" /></pb-stat>
          <pb-stat :label="$t('wallet.deposit')"><pb-amount :value="wallet.deposit" size="md" /></pb-stat>
          <pb-stat :label="$t('wallet.frozen')"><pb-amount :value="wallet.frozen" size="md" /></pb-stat>
        </view>
      </pb-card>

      <!-- 流水 -->
      <view class="mt-[28rpx] mb-[12rpx]"><text class="pb-h2">{{ $t("wallet.txns") }}</text></view>
      <pb-card :pad="false">
        <view class="px-[28rpx]">
          <view
            v-for="(x, i) in txns"
            :key="x.txnNo"
            class="pb-txn pb-rise"
            :style="{ animationDelay: i * 40 + 'ms' }"
          >
            <view class="pb-txn__chip"><pb-icon :name="txnIcon[x.type]" :size="34" /></view>
            <view class="flex-1">
              <text class="text-[28rpx] font-semibold text-ink">{{ x.title }}</text>
              <view class="mt-[4rpx] text-[22rpx] text-sub">{{ $t("wallet." + x.type) }} · {{ dateTimeOf(x.at) }}</view>
            </view>
            <text class="pb-num text-[30rpx] font-extrabold" :class="x.amount >= 0 ? 'text-success' : 'text-ink'">
              {{ x.amount >= 0 ? "+" : "-" }}{{ money(Math.abs(x.amount)) }}
            </text>
          </view>
        </view>
      </pb-card>
      <pb-empty v-if="!loading && !txns.length" icon="wallet" :text="$t('wallet.none')" />
    </view>
  </pb-scaffold>
</template>

<style scoped>
.pb-txn { display: flex; align-items: center; gap: 22rpx; padding: 22rpx 0; }
.pb-txn + .pb-txn { border-top: 1px solid var(--pb-faint); }
.pb-txn__chip {
  width: 66rpx;
  height: 66rpx;
  border-radius: 18rpx;
  background: var(--pb-faint);
  color: var(--pb-ink);
  display: flex;
  align-items: center;
  justify-content: center;
}
.text-success { color: var(--pb-success); }
</style>
