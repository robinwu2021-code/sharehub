<script setup lang="ts">
// 验证码输入：N 格色块 + 覆盖的隐藏 input 捕获键盘。
import { computed } from "vue";
const props = withDefaults(defineProps<{ modelValue?: string; length?: number }>(), { modelValue: "", length: 6 });
const emit = defineEmits<{ (e: "update:modelValue", v: string): void }>();
const cells = computed(() => Array.from({ length: props.length }, (_, i) => props.modelValue[i] || ""));
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function onInput(e: any) {
  const v = (e.detail.value || "").replace(/\D/g, "").slice(0, props.length);
  emit("update:modelValue", v);
}
</script>

<template>
  <view class="pb-otp">
    <input class="pb-otp__hidden" :value="modelValue" type="number" :maxlength="length" @input="onInput" />
    <view v-for="(c, i) in cells" :key="i" class="pb-otp__cell" :class="{ 'is-filled': c }">{{ c }}</view>
  </view>
</template>

<style scoped>
.pb-otp { position: relative; display: flex; gap: 16rpx; }
.pb-otp__hidden { position: absolute; left: 0; top: 0; width: 100%; height: 100%; opacity: 0; z-index: 2; }
.pb-otp__cell {
  flex: 1;
  height: 100rpx;
  border-radius: 20rpx;
  background: var(--pb-faint);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 42rpx;
  font-weight: 800;
  color: var(--pb-ink);
}
.pb-otp__cell.is-filled { background: var(--pb-primary-tint); color: var(--pb-primary); }
</style>
