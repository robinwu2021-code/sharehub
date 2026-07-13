<script setup lang="ts">
// 分段控件（色块滑块）。用于订单进行中/历史等筛选。
defineProps<{ options: { label: string; value: string }[]; modelValue: string }>();
const emit = defineEmits<{ (e: "update:modelValue", v: string): void }>();
</script>

<template>
  <view class="pb-seg">
    <view
      v-for="o in options"
      :key="o.value"
      class="pb-seg__item"
      :class="{ 'is-on': o.value === modelValue }"
      @tap="emit('update:modelValue', o.value)"
    >
      {{ o.label }}
    </view>
  </view>
</template>

<style scoped>
.pb-seg { display: flex; background: var(--pb-faint); border-radius: 9999px; padding: 6rpx; }
.pb-seg__item { flex: 1; text-align: center; padding: 14rpx 0; font-size: 26rpx; color: var(--pb-sub); border-radius: 9999px; transition: all 0.2s; }
.pb-seg__item.is-on { background: var(--pb-surface); color: var(--pb-ink); font-weight: 600; }
</style>
