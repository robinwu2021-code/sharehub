<script setup lang="ts">
/*
 * 问题反馈（报障）。
 *
 * 问题类型**不是前端写死的四个**，而是 `GET /mp/faq` 里的问题字典 ——
 * 后端按所选 `problemNo` 的 `suggestedAction` 决定这一单往哪走
 * （自助解答 / 转工单 / 转退款 / 转人工）。原来前端传的是本地写死的 `type`/`desc`，
 * 后端两个字段都不认，`problemNo` 恒为 null，于是**每一条报障都兜底进了人工队列**，
 * 而字典里配好的分流规则一次也没生效。这件事不报错，只是所有人都等人工。
 */
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "@/api";
import type { FaqItem } from "@/types";
import { t } from "@/i18n";

const faqs = ref<FaqItem[]>([]);
const problemNo = ref("");
const desc = ref("");
const photos = ref<string[]>([]);
const loading = ref(false);
let orderNo = "";
let cabinetNo = "";

/** 选中那条的自助答复：SELF_SERVICE 的问题，答复本身就是处理结果，先给人看到。 */
const picked = computed(() => faqs.value.find((f) => f.problemNo === problemNo.value) || null);

onLoad(async (q) => {
  orderNo = (q?.orderNo as string) || "";
  cabinetNo = (q?.cabinetNo as string) || "";
  try {
    faqs.value = await api.listFaq();
    if (faqs.value.length) problemNo.value = faqs.value[0].problemNo;
  } catch {
    faqs.value = [];
  }
});

function addPhoto() {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  uni.chooseImage({ count: 3, success: (r: any) => (photos.value = [...photos.value, ...r.tempFilePaths].slice(0, 3)) });
}
function removePhoto(i: number) {
  photos.value.splice(i, 1);
}
/** 提交后的提示按**后端给的出口**说，不要笼统说「已提交」——用户要知道接下来等什么。 */
const OUTCOME: Record<string, string> = {
  SELF_SERVICE: "feedback.doneSelf",
  TO_WORKORDER: "feedback.doneWorkOrder",
  TO_REFUND: "feedback.doneRefund",
  TO_CS: "feedback.doneCs",
};

async function submit() {
  if (!problemNo.value) {
    uni.showToast({ title: t("feedback.pickType"), icon: "none" });
    return;
  }
  loading.value = true;
  try {
    const r = await api.report({
      problemNo: problemNo.value,
      orderNo: orderNo || undefined,
      cabinetNo: cabinetNo || undefined,
      issue: desc.value,
    });
    uni.showToast({ title: t(OUTCOME[r.suggestedAction] || "feedback.submitted"), icon: "none" });
    // 跳到进度页而不是退回去：报障之后用户最想知道的是「现在到哪一步了」
    setTimeout(() => uni.navigateTo({ url: "/pages/reports/index" }), 800);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <pb-scaffold :title="$t('feedback.title')" show-back>
    <view class="px-[32rpx] pt-[24rpx]">
      <text class="text-[24rpx] text-sub">{{ $t("feedback.typeLabel") }}</text>
      <view class="mt-[16rpx] flex flex-wrap gap-[16rpx]">
        <view
          v-for="f in faqs"
          :key="f.problemNo"
          class="pb-chip"
          :class="{ 'is-on': problemNo === f.problemNo }"
          @tap="problemNo = f.problemNo"
        >
          {{ f.title }}
        </view>
      </view>
      <!-- 字典里的自助答复。很多问题到这一步就解决了，不必先提交再等回复 -->
      <view v-if="picked && picked.answer" class="mt-[20rpx]">
        <pb-card tint="primary">
          <text class="text-[26rpx] text-ink" style="line-height: 1.7">{{ picked.answer }}</text>
        </pb-card>
      </view>

      <text class="mt-[32rpx] block text-[24rpx] text-sub">{{ $t("feedback.descLabel") }}</text>
      <textarea v-model="desc" class="pb-ta mt-[16rpx]" :placeholder="$t('feedback.descPh')" />

      <!-- ⚠️ 这些图目前只留在端上：后端 ReportReq 没有附件字段，也没有上传端点。
           在补上之前它是个摆设，别据此以为客服能看到图（见交付报告的待办） -->
      <view class="mt-[24rpx] flex flex-wrap gap-[16rpx]">
        <view v-for="(p, i) in photos" :key="i" class="pb-photo" @tap="removePhoto(i)">
          <image :src="p" class="pb-photo__img" mode="aspectFill" />
        </view>
        <view v-if="photos.length < 3" class="pb-photo pb-photo--add" @tap="addPhoto"><pb-icon name="camera" :size="44" /></view>
      </view>

      <view class="mt-[40rpx]"><pb-button block size="lg" :loading="loading" @click="submit">{{ $t("feedback.submit") }}</pb-button></view>
    </view>
  </pb-scaffold>
</template>

<style scoped>
.pb-chip { padding: 14rpx 28rpx; border-radius: 9999px; background: var(--pb-faint); color: var(--pb-sub); font-size: 26rpx; font-weight: 600; }
.pb-chip.is-on { background: var(--pb-primary-tint); color: var(--pb-primary); }
.pb-ta { display: block; width: 100%; height: 220rpx; background: var(--pb-faint); border-radius: 22rpx; padding: 24rpx; font-size: 28rpx; color: var(--pb-ink); box-sizing: border-box; }
.pb-photo { width: 150rpx; height: 150rpx; border-radius: 20rpx; overflow: hidden; background: var(--pb-faint); display: flex; align-items: center; justify-content: center; color: var(--pb-sub); }
.pb-photo__img { width: 100%; height: 100%; }
.pb-photo--add { border: 2rpx dashed var(--pb-sub); }
</style>
