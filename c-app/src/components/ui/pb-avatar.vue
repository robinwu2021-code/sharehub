<script setup lang="ts">
import { computed } from "vue";
const props = withDefaults(defineProps<{ icon?: string; size?: number }>(), { icon: "user", size: 96 });
const px = computed(() => `${props.size}rpx`);
const iconSize = computed(() => Math.round(props.size * 0.5));
</script>

<template>
  <view class="pb-avatar" :style="{ width: px, height: px }"><pb-icon :name="props.icon" :size="iconSize" /></view>
</template>

<style scoped>
.pb-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 9999px;
  background: var(--pb-primary-tint);
  color: var(--pb-primary);
}
</style>
