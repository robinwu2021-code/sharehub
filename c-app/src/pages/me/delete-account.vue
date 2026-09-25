<script setup lang="ts">
/*
 * 注销账户（PDPL 冷静期）。
 *
 * ⚠️ 这个页面此前是**假的**：点确认只做了 userStore.logout() 然后弹「OK」——
 * 账号根本没注销，而用户以为注销了。合规功能装成能用，比没有更糟。
 *
 * 现在接真接口，并且**申请之后不登出**：冷静期内可以撤销，
 * 把人踢下线等于把那扇回头的门关上（还得重新收验证码才能进来撤）。
 */
import { onMounted, ref } from "vue";
import { api } from "@/api";
import type { LogoffItem } from "@/types";
import { useI18n } from "vue-i18n";

const { t } = useI18n();
const show = ref(false);
const busy = ref(false);
const pending = ref<LogoffItem | null>(null);

/** 后端给的是 `yyyy-MM-dd HH:mm:ss`，界面只需要到天。 */
const untilDay = (v: string) => (v || "").slice(0, 10);

async function load() {
  try {
    const cur = await api.currentLogoff();
    pending.value = cur && cur.status === "PENDING" ? cur : null;
  } catch {
    pending.value = null; // 查不到就按「没有申请」渲染，不要把页面卡在加载态
  }
}
onMounted(load);

async function confirmDelete() {
  if (busy.value) return;
  busy.value = true;
  try {
    pending.value = await api.applyLogoff();
    uni.showToast({ title: t("settings.deleteApplied"), icon: "none" });
  } catch {
    uni.showToast({ title: t("settings.deleteFailed"), icon: "none" });
  } finally {
    busy.value = false;
  }
}

async function revoke() {
  if (busy.value) return;
  busy.value = true;
  try {
    await api.cancelLogoff();
    pending.value = null;
    uni.showToast({ title: t("settings.deleteRevoked"), icon: "success" });
  } catch {
    // 冷静期过了会落到这里（后端 400）——此时数据可能已在清除，不能假装撤销成功
    uni.showToast({ title: t("settings.deleteFailed"), icon: "none" });
    await load();
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <pb-scaffold :title="$t('settings.deleteAccount')" show-back>
    <view class="px-[32rpx] pt-[24rpx]">
      <!-- 已申请：显示生效日期 + 撤销入口。不显示日期的话，用户不知道哪天会真删 -->
      <template v-if="pending">
        <pb-card tint="danger">
          <view class="flex items-start gap-[16rpx]">
            <pb-icon name="info" :size="40" class="text-danger" />
            <view class="flex-1">
              <text class="text-[28rpx] font-medium text-ink">{{ $t("settings.deletePending") }}</text>
              <text class="mt-[8rpx] block text-[26rpx] text-ink-2" style="line-height: 1.7">
                {{ $t("settings.deleteCoolingUntil", { until: untilDay(pending.coolingUntil) }) }}
              </text>
            </view>
          </view>
        </pb-card>
        <view class="mt-[44rpx]">
          <pb-button block size="lg" :loading="busy" @click="revoke">{{ $t("settings.deleteRevoke") }}</pb-button>
        </view>
      </template>

      <template v-else>
        <pb-card tint="danger">
          <view class="flex items-start gap-[16rpx]">
            <pb-icon name="info" :size="40" class="text-danger" />
            <text class="flex-1 text-[26rpx] text-ink" style="line-height: 1.7">{{ $t("settings.deleteWarn") }}</text>
          </view>
        </pb-card>
        <view class="mt-[44rpx]">
          <pb-button block size="lg" type="danger" :loading="busy" @click="show = true">
            {{ $t("settings.deleteConfirm") }}
          </pb-button>
        </view>
      </template>
    </view>

    <pb-dialog
      v-model:visible="show"
      :title="$t('settings.deleteConfirm')"
      :message="$t('settings.deleteWarn')"
      :confirm-text="$t('settings.deleteConfirm')"
      danger
      @confirm="confirmDelete"
    />
  </pb-scaffold>
</template>
