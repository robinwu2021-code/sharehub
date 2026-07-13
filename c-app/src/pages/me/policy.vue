<script setup lang="ts">
import { computed } from "vue";
import { useAppStore } from "@/stores/app";
const app = useAppStore();
const texts: Record<string, string> = {
  en: "We respect your privacy. Personal data is stored in compliance with PDPL. Card details are never stored on device (handled by the payment provider). You may request export or deletion of your data at any time from Settings › Delete account.",
  zh: "我们尊重你的隐私。个人数据依 PDPL（阿联酋个人数据保护法）合规存储。银行卡信息不在设备留存（由支付方托管）。你可随时在「设置 › 注销账户」申请导出或删除数据。",
  ar: "نحترم خصوصيتك. تُخزَّن البيانات الشخصية وفقًا لقانون حماية البيانات (PDPL). لا تُحفظ بيانات البطاقة على الجهاز (يديرها مزود الدفع). يمكنك طلب تصدير بياناتك أو حذفها في أي وقت من الإعدادات › حذف الحساب.",
};
const body = computed(() => texts[app.lang] ?? texts.en);
</script>

<template>
  <pb-scaffold :title="$t('settings.privacy')" show-back>
    <view class="px-[32rpx] pt-[24rpx]">
      <text class="pb-h2">{{ $t("settings.privacy") }}</text>
      <text class="mt-[24rpx] block text-[26rpx] text-sub" style="line-height: 1.8">{{ body }}</text>
    </view>
  </pb-scaffold>
</template>
