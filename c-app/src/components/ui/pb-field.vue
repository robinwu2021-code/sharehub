<script setup lang="ts">
// 表单输入行：可选 label + 前/后缀插槽。色块底、无边框。
withDefaults(
  defineProps<{ modelValue?: string; placeholder?: string; type?: string; label?: string; password?: boolean }>(),
  { type: "text", modelValue: "" },
);
const emit = defineEmits<{ (e: "update:modelValue", v: string): void }>();
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function onInput(e: any) {
  emit("update:modelValue", e.detail.value);
}
</script>

<template>
  <view class="pb-field">
    <text v-if="label" class="pb-field__label">{{ label }}</text>
    <view class="pb-field__box">
      <slot name="prefix" />
      <input
        class="pb-field__input"
        :value="modelValue"
        :type="password ? 'text' : type"
        :password="password"
        :placeholder="placeholder"
        @input="onInput"
      />
      <slot name="suffix" />
    </view>
  </view>
</template>

<style scoped>
.pb-field__label { display: block; font-size: 24rpx; color: var(--pb-sub); margin-bottom: 12rpx; }
.pb-field__box { display: flex; align-items: center; gap: 14rpx; background: var(--pb-faint); border-radius: 22rpx; padding: 0 26rpx; height: 96rpx; }
.pb-field__input { flex: 1; height: 96rpx; font-size: 30rpx; color: var(--pb-ink); }
</style>
