<script setup lang="ts">
/*
 * 优惠券：领券中心 + 我的券包。
 *
 * 这两份是不同的东西，键也不同 —— 券包里是**券实例**（couponNo，CP…），
 * 领券中心里是**券模板**（tplNo）。领券要传 tplNo；传成 couponNo 的表现是
 * 400「券模板不存在」，看着像脏数据，其实是传了另一张表的主键。
 *
 * 此前这页只有券包，而且读的是 `title`/`amount` —— 后端回的是 `tplName`/`value`，
 * 切到真后端时卡片上的券名和金额全是空的，页面不报错。
 */
import { ref, computed } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import type { UserCoupon, ClaimableCoupon } from "@/types";
import { money } from "@/shared/format";

const { t } = useI18n();
const tab = ref("center");
const mine = ref<UserCoupon[]>([]);
const claimable = ref<ClaimableCoupon[]>([]);
const loading = ref(true);
const claiming = ref("");

const options = computed(() => [
  { label: t("coupon.center"), value: "center" },
  { label: t("coupon.mine"), value: "mine" },
]);

const tagType: Record<UserCoupon["status"], "success" | "neutral"> = {
  UNUSED: "success",
  USED: "neutral",
  EXPIRED: "neutral",
};
const statusKey: Record<UserCoupon["status"], string> = {
  UNUSED: "coupon.unused",
  USED: "coupon.used",
  EXPIRED: "coupon.expired",
};

/** 折扣券的 value 是 0..1 的折扣率，直接显示 0.8 没人看得懂，换算成「20% 折扣」。 */
const percentOff = (v: number) => Math.round((1 - v) * 100);

async function load() {
  loading.value = true;
  try {
    // 两份一起拉：领完要立刻能在券包里看到，分开加载会出现「领了但券包还是旧的」
    const [c, m] = await Promise.all([api.listClaimableCoupons(), api.listCoupons()]);
    claimable.value = c;
    mine.value = m;
  } finally {
    loading.value = false;
  }
}
onShow(load);

async function claim(c: ClaimableCoupon) {
  if (c.claimed || claiming.value) return;
  claiming.value = c.tplNo;
  try {
    await api.claimCoupon(c.tplNo);
    uni.showToast({ title: t("coupon.claimOk"), icon: "none" });
    await load(); // 重新拉：claimed 与剩余张数都由后端说了算，不在前端猜
  } catch {
    uni.showToast({ title: t("coupon.claimFailed"), icon: "none" });
  } finally {
    claiming.value = "";
  }
}
</script>

<template>
  <pb-scaffold :title="$t('me.coupons')" show-back>
    <view class="px-[32rpx] pt-[24rpx]">
      <pb-segmented v-model="tab" :options="options" />
    </view>

    <!-- 领券中心 -->
    <view v-if="tab === 'center'" class="flex flex-col gap-[20rpx] px-[32rpx] pt-[24rpx]">
      <pb-card
        v-for="(c, i) in claimable"
        :key="c.tplNo"
        class="pb-rise"
        :style="{ animationDelay: i * 50 + 'ms' }"
      >
        <view class="flex items-center">
          <view class="pb-cpn is-live">
            <view v-if="c.type === 'CUT'" class="flex items-baseline">
              <text class="text-[24rpx] font-bold">{{ $t("common.currency") }}</text>
              <text class="pb-num ms-[4rpx] text-[60rpx] font-extrabold leading-none">{{ c.value }}</text>
            </view>
            <text v-else class="pb-num text-[44rpx] font-extrabold leading-none">
              {{ percentOff(c.value) }}%
            </text>
            <text class="mt-[2rpx] text-[20rpx] font-bold">
              {{ c.type === "CUT" ? $t("coupon.off") : $t("coupon.discount") }}
            </text>
          </view>
          <view class="ms-[24rpx] flex-1">
            <text class="text-[30rpx] font-bold text-ink">{{ c.name }}</text>
            <view class="mt-[8rpx] text-[22rpx] text-sub">
              {{ c.threshold > 0 ? $t("coupon.min") + " " + money(c.threshold) : $t("coupon.noMin") }}
            </view>
            <!-- remaining 为 null 是「不限量」，不是「剩 0 张」——所以这行只在有限量时出现 -->
            <view v-if="c.remaining !== null" class="mt-[4rpx] text-[22rpx] text-warning">
              {{ $t("coupon.remaining", { n: c.remaining }) }}
            </view>
          </view>
          <pb-button
            size="sm"
            :disabled="c.claimed"
            :loading="claiming === c.tplNo"
            @click="claim(c)"
          >
            {{ c.claimed ? $t("coupon.claimed") : $t("coupon.claim") }}
          </pb-button>
        </view>
      </pb-card>
      <pb-empty v-if="!loading && !claimable.length" icon="ticket" :text="$t('coupon.noneClaimable')" />
    </view>

    <!-- 我的券包 -->
    <view v-else class="flex flex-col gap-[20rpx] px-[32rpx] pt-[24rpx]">
      <pb-card
        v-for="(c, i) in mine"
        :key="c.couponNo"
        :flat="c.status !== 'UNUSED'"
        class="pb-rise"
        :style="{ animationDelay: i * 50 + 'ms' }"
      >
        <view class="flex items-center" :class="{ 'is-dim': c.status !== 'UNUSED' }">
          <view class="pb-cpn" :class="{ 'is-live': c.status === 'UNUSED' }">
            <view v-if="c.tplType === 'CUT'" class="flex items-baseline">
              <text class="text-[24rpx] font-bold">{{ $t("common.currency") }}</text>
              <text class="pb-num ms-[4rpx] text-[60rpx] font-extrabold leading-none">{{ c.value }}</text>
            </view>
            <text v-else class="pb-num text-[44rpx] font-extrabold leading-none">{{ percentOff(c.value) }}%</text>
            <text class="mt-[2rpx] text-[20rpx] font-bold">
              {{ c.tplType === "CUT" ? $t("coupon.off") : $t("coupon.discount") }}
            </text>
          </view>
          <view class="ms-[24rpx] flex-1">
            <text class="text-[30rpx] font-bold text-ink">{{ c.tplName }}</text>
            <view class="mt-[8rpx] text-[22rpx] text-sub">
              {{ c.threshold > 0 ? $t("coupon.min") + " " + money(c.threshold) : $t("coupon.noMin") }}
            </view>
            <view class="mt-[4rpx] text-[22rpx] text-sub">{{ $t("coupon.validTo") }} {{ c.expireAt }}</view>
          </view>
          <pb-tag :type="tagType[c.status]">{{ $t(statusKey[c.status]) }}</pb-tag>
        </view>
      </pb-card>
      <pb-empty v-if="!loading && !mine.length" icon="ticket" :text="$t('coupon.none')" />
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
