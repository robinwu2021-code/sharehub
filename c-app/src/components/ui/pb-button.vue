<script setup lang="ts">
// 扁平主按钮：primary 实心 / tonal 色块底 / ghost 透明 / danger。皮肤色由 CSS 变量驱动。按压有回弹反馈。
withDefaults(
  defineProps<{
    type?: "primary" | "tonal" | "ghost" | "danger";
    size?: "sm" | "md" | "lg";
    block?: boolean;
    loading?: boolean;
    disabled?: boolean;
  }>(),
  { type: "primary", size: "md", block: false, loading: false, disabled: false },
);
const emit = defineEmits<{ (e: "click"): void }>();
</script>

<template>
  <view
    class="pb-btn"
    :class="[`pb-btn--${type}`, `pb-btn--${size}`, block ? 'pb-btn--block' : '', disabled || loading ? 'is-disabled' : '']"
    hover-class="pb-btn--press"
    :hover-stay-time="40"
    @tap.stop="!disabled && !loading && emit('click')"
  >
    <text v-if="loading" class="pb-btn__spin">◌</text>
    <slot />
  </view>
</template>

<style scoped>
.pb-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 10rpx;
  font-weight: 700;
  letter-spacing: 0.2rpx;
  border-radius: 9999px;
  line-height: 1;
  transition: transform 0.12s ease, opacity 0.12s ease, box-shadow 0.2s ease;
}
.pb-btn--block { width: 100%; }
.pb-btn--sm { height: 60rpx; padding: 0 30rpx; font-size: 25rpx; }
.pb-btn--md { height: 84rpx; padding: 0 44rpx; font-size: 29rpx; }
.pb-btn--lg { height: 96rpx; padding: 0 52rpx; font-size: 31rpx; }
.pb-btn--primary { background: var(--pb-primary); color: var(--pb-on-primary); box-shadow: 0 8rpx 22rpx var(--pb-primary-tint); }
.pb-btn--tonal { background: var(--pb-primary-tint); color: var(--pb-primary); }
.pb-btn--ghost { background: transparent; color: var(--pb-primary); }
.pb-btn--danger { background: var(--pb-danger); color: #fff; }
.pb-btn--press { transform: scale(0.965); opacity: 0.92; }
.is-disabled { opacity: 0.42; box-shadow: none; }
.pb-btn__spin { display: inline-block; animation: pbspin 0.9s linear infinite; }
@keyframes pbspin { to { transform: rotate(360deg); } }
</style>
