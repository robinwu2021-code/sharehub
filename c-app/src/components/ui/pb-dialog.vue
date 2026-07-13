<script setup lang="ts">
// 确认对话框（注销/退出等）。danger=危险操作红色确认。
withDefaults(
  defineProps<{ visible: boolean; title?: string; message?: string; confirmText?: string; cancelText?: string; danger?: boolean }>(),
  { danger: false },
);
const emit = defineEmits<{ (e: "update:visible", v: boolean): void; (e: "confirm"): void }>();
function cancel() {
  emit("update:visible", false);
}
function ok() {
  emit("confirm");
  emit("update:visible", false);
}
</script>

<template>
  <view v-if="visible" class="pb-dlg">
    <view class="pb-dlg__mask" @tap="cancel" />
    <view class="pb-dlg__box">
      <text class="pb-dlg__title">{{ title }}</text>
      <text v-if="message" class="pb-dlg__msg">{{ message }}</text>
      <view class="pb-dlg__btns">
        <view class="pb-dlg__btn is-cancel" @tap="cancel">{{ cancelText || $t("common.cancel") }}</view>
        <view class="pb-dlg__btn" :class="danger ? 'is-danger' : 'is-ok'" @tap="ok">{{ confirmText || $t("common.confirm") }}</view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.pb-dlg { position: fixed; inset: 0; z-index: 110; display: flex; align-items: center; justify-content: center; }
.pb-dlg__mask { position: absolute; inset: 0; background: rgba(0, 0, 0, 0.45); }
.pb-dlg__box { position: relative; width: 78%; background: var(--pb-elev); border-radius: 30rpx; padding: 40rpx 36rpx 28rpx; }
.pb-dlg__title { display: block; text-align: center; font-size: 32rpx; font-weight: 800; color: var(--pb-ink); }
.pb-dlg__msg { display: block; text-align: center; font-size: 26rpx; color: var(--pb-sub); margin-top: 16rpx; line-height: 1.6; }
.pb-dlg__btns { display: flex; gap: 16rpx; margin-top: 36rpx; }
.pb-dlg__btn { flex: 1; text-align: center; padding: 24rpx 0; border-radius: 9999px; font-size: 29rpx; font-weight: 700; }
.is-cancel { background: var(--pb-faint); color: var(--pb-ink); }
.is-ok { background: var(--pb-primary); color: var(--pb-on-primary); }
.is-danger { background: var(--pb-danger); color: #fff; }
</style>
