<script setup lang="ts">
/*
 * 钱包：余额/赠金/押金/免押冻结 概览 + 充值 + 交易流水。
 *
 * 原来的「充值」按钮打的是 `pay({scene:"recharge", amount: 20})` —— 两个问题：
 * 金额写死 20（用户没得选，套餐的赠金也永远拿不到），而且后端那个口在没有
 * orderNo 时直接抛「充值等无单支付待充值单流程接入」。这里又没有 catch，
 * 于是点下去**什么都不发生**：没提示、没报错，只有按钮转一下。
 * 现在改走 `POST /mp/user/recharge`，并且**只传套餐号**（金额由服务端按套餐算）。
 */
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import type { Wallet, WalletTxn, RechargePackage } from "@/types";
import { money, dateTimeOf } from "@/shared/format";

const { t } = useI18n();
const wallet = ref<Wallet | null>(null);
const txns = ref<WalletTxn[]>([]);
const packages = ref<RechargePackage[]>([]);
const loading = ref(true);
const sheet = ref(false);
const paying = ref("");

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

async function openSheet() {
  sheet.value = true;
  if (packages.value.length) return;
  try {
    packages.value = await api.listRechargePackages();
  } catch {
    packages.value = [];
  }
}

const txnIcon: Record<WalletTxn["type"], string> = {
  RECHARGE: "plus",
  SPEND: "zap",
  REFUND: "navigation",
  BONUS: "sparkles",
};

async function recharge(p: RechargePackage) {
  if (paying.value) return;
  paying.value = p.packageNo;
  try {
    const r = await api.recharge(p.packageNo);
    sheet.value = false;
    if (r.status === "PAID") {
      uni.showToast({ title: t("wallet.rechargeOk", { n: money(r.creditAmount) }), icon: "none" });
    } else {
      // 通道没即时成功：单据在，钱没到。说「成功」会让用户以为钱到了却看不到余额变化
      uni.showToast({ title: t("wallet.rechargePending"), icon: "none" });
    }
    await load();
  } catch {
    uni.showToast({ title: t("wallet.rechargeFailed"), icon: "none" });
  } finally {
    paying.value = "";
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
          <pb-button size="sm" @click="openSheet">{{ $t("wallet.recharge") }}</pb-button>
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
              <view class="mt-[4rpx] text-[22rpx] text-sub">{{ $t("wallet." + x.type) }} · {{ dateTimeOf(x.createdAt) }}</view>
            </view>
            <text class="pb-num text-[30rpx] font-extrabold" :class="x.amount >= 0 ? 'text-success' : 'text-ink'">
              {{ x.amount >= 0 ? "+" : "-" }}{{ money(Math.abs(x.amount)) }}
            </text>
          </view>
        </view>
      </pb-card>
      <pb-empty v-if="!loading && !txns.length" icon="wallet" :text="$t('wallet.none')" />
    </view>

    <!-- 充值套餐选择。金额只由套餐决定，页面不提供「自定义金额」输入 ——
         端上传金额的接口后端也不收，摆个输入框只会让人以为能填 -->
    <view v-if="sheet" class="pb-sheet">
      <view class="pb-sheet__mask" @tap="sheet = false" />
      <view class="pb-sheet__panel">
        <view class="pb-sheet__grip" />
        <text class="pb-sheet__title">{{ $t("wallet.recharge") }}</text>
        <view class="mt-[20rpx] flex flex-col gap-[16rpx]">
          <view
            v-for="p in packages"
            :key="p.packageNo"
            class="pb-pkg"
            :class="{ 'is-busy': paying === p.packageNo }"
            @tap="recharge(p)"
          >
            <view class="flex-1">
              <text class="text-[30rpx] font-bold text-ink">{{ p.name }}</text>
              <view v-if="p.giftAmount > 0" class="mt-[4rpx] text-[22rpx] text-success">
                {{ $t("wallet.rechargeGift", { n: money(p.giftAmount) }) }}
              </view>
            </view>
            <pb-amount :value="p.payAmount" size="md" />
          </view>
        </view>
        <pb-empty v-if="!packages.length" icon="wallet" :text="$t('wallet.noPackages')" />
      </view>
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
.pb-sheet { position: fixed; inset: 0; z-index: 100; }
.pb-sheet__mask { position: absolute; inset: 0; background: rgba(0, 0, 0, 0.4); }
.pb-sheet__panel {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  background: var(--pb-surface);
  border-radius: 32rpx 32rpx 0 0;
  padding: 20rpx 32rpx calc(40rpx + env(safe-area-inset-bottom));
}
.pb-sheet__grip { width: 72rpx; height: 8rpx; border-radius: 9999px; background: var(--pb-faint); margin: 0 auto 20rpx; }
.pb-sheet__title { display: block; text-align: center; font-size: 32rpx; font-weight: 700; color: var(--pb-ink); margin-bottom: 8rpx; }
.pb-pkg {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 26rpx 28rpx;
  border-radius: 24rpx;
  background: var(--pb-faint);
}
.pb-pkg.is-busy { opacity: 0.5; }
</style>
