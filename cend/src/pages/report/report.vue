<script setup lang="ts">
import { ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import type { ReportType } from "@/api/types";

const { t } = useI18n();
const orderNo = ref<string | undefined>(undefined);
const type = ref<ReportType>("NOT_EJECTED");
const desc = ref("");
const submitting = ref(false);

const types: { v: ReportType; label: string }[] = [
  { v: "NOT_EJECTED", label: "report.notEjected" },
  { v: "CANNOT_RETURN", label: "report.cannotReturn" },
  { v: "OVERCHARGE", label: "report.overcharge" },
  { v: "OTHER", label: "report.other" },
];

onLoad((opts) => {
  orderNo.value = opts?.orderNo || undefined;
});

async function submit() {
  submitting.value = true;
  try {
    await api.report({ orderNo: orderNo.value, type: type.value, desc: desc.value });
    uni.showToast({ title: t("report.done"), icon: "success" });
    setTimeout(() => uni.navigateBack(), 600);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <view class="p-3">
    <view class="card">
      <text class="text-gray-500 text-sm">{{ t("report.type") }}</text>
      <view class="flex flex-wrap gap-2 mt-2">
        <view
          v-for="it in types"
          :key="it.v"
          class="px-3 py-1 rounded-full text-sm border"
          :class="type === it.v ? 'bg-brand text-white border-brand' : 'border-gray-200 text-gray-500'"
          @click="type = it.v"
        >
          {{ t(it.label) }}
        </view>
      </view>
    </view>

    <view class="card mt-3">
      <textarea
        v-model="desc"
        class="w-full h-24 text-base"
        :placeholder="t('report.desc')"
      />
    </view>

    <view
      class="mt-6 bg-brand text-white rounded-xl py-3 text-center font-semibold active:opacity-80"
      :class="submitting ? 'opacity-60' : ''"
      @click="submit"
    >
      {{ t("report.submit") }}
    </view>
  </view>
</template>
