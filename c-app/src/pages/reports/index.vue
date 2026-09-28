<script setup lang="ts">
/*
 * 我的报障（进度）。
 *
 * 后端 `GET /mp/user/reports` 与 `/{reportNo}` 一直在，但 C 端从来没有这一页 ——
 * 于是提交报障之后用户**再也看不到它**：不知道受理了没有、有没有转成工单或退款。
 * 唯一能做的事是再报一次，而那只会在人工队列里多一条重复单。
 *
 * 展示的关键不是「状态」三个字，而是**处置去向**：woNo / refundNo 决定了
 * 「在修」还是「在退钱」，这两者对用户意味着完全不同的等待。
 */
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import type { CsTicket } from "@/types";
import { stampOf } from "@/shared/format";
import { t } from "@/i18n";

const list = ref<CsTicket[]>([]);
const loading = ref(true);
const failed = ref(false);

const tagType: Record<CsTicket["status"], "primary" | "warning" | "neutral"> = {
  OPEN: "warning",
  PROCESSING: "primary",
  CLOSED: "neutral",
};

async function load() {
  loading.value = true;
  failed.value = false;
  try {
    const r = await api.listReports({ size: 50 });
    list.value = r.list;
  } catch {
    failed.value = true;
  } finally {
    loading.value = false;
  }
}
onShow(load);

/**
 * 看自己交上去的照片。
 *
 * 列表只带张数，图要按 fileNo 逐张取（个人数据，服务端不发公开链接，每次都要带 token）。
 * 点开才取：一屏 50 条报障单如果每条都预下载几张图，进页面就是几十个请求。
 */
const opening = ref("");

function openCs() {
  uni.navigateTo({ url: "/pages/cs/index" });
}
async function viewPhotos(k: CsTicket) {
  if (!k.photoCount || opening.value) return;
  opening.value = k.ticketNo;
  uni.showLoading({ title: t("common.loading"), mask: true });
  try {
    const detail = await api.getReport(k.ticketNo);
    const nos = (detail?.photos ?? []).map((p) => p.fileNo);
    const paths = await Promise.all(nos.map((no) => api.fetchEvidence(no)));
    if (!paths.length) throw new Error(t("common.loadFailed"));
    uni.previewImage({ urls: paths });
  } catch (e) {
    uni.showToast({ title: (e as Error).message || t("common.loadFailed"), icon: "none" });
  } finally {
    uni.hideLoading();
    opening.value = "";
  }
}
</script>

<template>
  <pb-scaffold :title="$t('reports.title')" show-back>
    <view class="flex flex-col gap-[20rpx] px-[32rpx] pt-[24rpx]">
      <pb-card
        v-for="(k, i) in list"
        :key="k.ticketNo"
        class="pb-rise"
        :style="{ animationDelay: i * 50 + 'ms' }"
      >
        <view class="flex items-start justify-between">
          <view class="flex-1">
            <text class="text-[29rpx] font-bold text-ink">{{ k.issue || k.ticketNo }}</text>
            <view class="mt-[6rpx] text-[22rpx] text-sub"><text class="pb-ltr">{{ k.ticketNo }}</text> · <text class="pb-ltr">{{ stampOf(k.createdAt) }}</text></view>
          </view>
          <pb-tag :type="tagType[k.status]">{{ $t("reports." + k.status) }}</pb-tag>
        </view>

        <!-- 去向。只显示状态的话，「处理中」对用户等于什么都没说 -->
        <view class="mt-[20rpx] flex flex-col gap-[8rpx]">
          <view v-if="k.woNo" class="pb-row">
            <pb-icon name="receipt" :size="32" class="text-sub" />
            <text class="text-[24rpx] text-ink">{{ $t("reports.toWorkOrder", { no: k.woNo }) }}</text>
          </view>
          <view v-if="k.refundNo" class="pb-row">
            <pb-icon name="wallet" :size="32" class="text-sub" />
            <text class="text-[24rpx] text-ink">{{ $t("reports.toRefund", { no: k.refundNo }) }}</text>
          </view>
          <view v-if="k.orderNo" class="pb-row">
            <pb-icon name="zap" :size="32" class="text-sub" />
            <text class="text-[24rpx] text-ink">{{ $t("reports.onOrder", { no: k.orderNo }) }}</text>
          </view>
          <!--
            交上去的照片：给出张数并且能点开看。
            只写「已附 N 张」而点不开，用户仍然无法确认交的是不是那张 —— 那是把一句话换成了另一句话。
          -->
          <view v-if="k.photoCount" class="pb-row" @tap="viewPhotos(k)">
            <pb-icon name="camera" :size="32" class="text-sub" />
            <text class="text-[24rpx] text-primary">{{ $t("reports.photos", { n: k.photoCount }) }}</text>
          </view>
          <!--
            既没工单也没退款、状态却是处理中 = 在人工队列里排着。
            这一行要能点进会话 —— 只说「正在排队处理」而没有入口，正是此前那条死胡同：
            客服回了什么用户看不到，也没法追问。
          -->
          <view v-if="!k.woNo && !k.refundNo && k.status !== 'CLOSED'" class="pb-row" @tap="openCs">
            <pb-icon name="message" :size="32" class="text-sub" />
            <text class="text-[24rpx] text-primary">{{ $t("reports.inQueue") }} · {{ $t("cs.entry") }}</text>
          </view>
        </view>
      </pb-card>
      <pb-empty
        v-if="!loading && failed"
        icon="info"
        :text="$t('common.loadFailed')"
        :action-text="$t('common.retry')"
        @action="load"
      />
      <pb-empty v-if="!loading && !failed && !list.length" icon="info" :text="$t('reports.none')" />
    </view>
  </pb-scaffold>
</template>

<style scoped>
.pb-row { display: flex; align-items: center; gap: 12rpx; }
</style>
