<script setup lang="ts">
/**
 * 人工客服会话。
 *
 * 在此之前这一页不存在：报障被转人工之后，进度页永远停在「已转人工客服，正在排队处理」——
 * 客服回了什么用户看不到，也没法追问。而「转人工」是分流的兜底方向，也就是最常走的那条。
 *
 * 取数：进页面拉一次；发完一句重拉（消息是 append 表，一次取整段，量有界）。
 * 不做轮询/长连：那要先有推送与在线状态，是另一件事；现在下拉刷新足够诚实。
 */
import { computed, nextTick, ref } from "vue";
import { onShow, onPullDownRefresh, onReady } from "@dcloudio/uni-app";
import { api } from "@/api";
import { stampOf } from "@/shared/format";
import { t } from "@/i18n";
import type { CsMessage, CsSession } from "@/types";

const session = ref<CsSession | null>(null);
const msgs = ref<CsMessage[]>([]);
const draft = ref("");
const loading = ref(true);
const failed = ref(false);
const sending = ref(false);
/** 滚到底：新消息在最下面，进页面该看到最新那句而不是三天前那句 */
const scrollTo = ref("");

const closed = computed(() => session.value?.status === "CLOSED");

/*
 * 列表高度按屏算，不用 flex:1 —— pb-scaffold 的 body 本身是个 scroll-view，
 * 放在它里面的 flex 子项撑不开（真机实测：输入框跟在消息后面，而不是沉在底部）。
 * 与 pages/scan 同一做法：屏高 − 状态栏 − 标题栏 − 输入条 − 手势条安全区。
 */
const BAR_RPX = 104;
const listH = ref(0);
onReady(() => {
  const sys = uni.getSystemInfoSync();
  listH.value = Math.max(
    200,
    sys.windowHeight - (sys.statusBarHeight ?? 0) - uni.upx2px(88) - uni.upx2px(BAR_RPX) - (sys.safeAreaInsets?.bottom ?? 0),
  );
});

async function load() {
  loading.value = true;
  failed.value = false;
  try {
    session.value = await api.myCsSession();
    msgs.value = session.value ? await api.listCsMessages(session.value.sessionNo) : [];
    await nextTick();
    scrollTo.value = msgs.value.length ? "m" + msgs.value[msgs.value.length - 1].id : "";
  } catch {
    failed.value = true;
  } finally {
    loading.value = false;
  }
}
onShow(load);
onPullDownRefresh(async () => {
  await load();
  uni.stopPullDownRefresh();
});

async function send() {
  const text = draft.value.trim();
  if (!text || sending.value || !session.value) return;
  sending.value = true;
  try {
    await api.sendCsMessage(session.value.sessionNo, text);
    draft.value = "";
    await load();
  } catch (e) {
    // 发失败不能清空输入框：用户打的字是他的劳动，丢了要重打一遍
    uni.showToast({ title: (e as Error).message || t("common.loadFailed"), icon: "none" });
  } finally {
    sending.value = false;
  }
}
</script>

<template>
  <pb-scaffold :title="$t('cs.title')" show-back>
    <scroll-view
      scroll-y
      class="pb-cs__list"
      :style="{ height: listH + 'px' }"
      :scroll-into-view="scrollTo"
      scroll-with-animation
    >
      <view v-if="loading" class="pb-cs__hint">{{ $t("common.loading") }}</view>
      <pb-empty
        v-else-if="failed"
        icon="info"
        :text="$t('common.loadFailed')"
        :action-text="$t('common.retry')"
        @action="load"
      />
      <!-- 没有会话 ≠ 出错：说清楚「什么时候会有」，而不是一句泛化空态 -->
      <pb-empty v-else-if="!session" icon="message" :text="$t('cs.noSession')" />
      <template v-else>
        <view
          v-for="m in msgs"
          :id="'m' + m.id"
          :key="m.id"
          class="pb-cs__row"
          :class="m.senderType === 'USER' ? 'is-me' : 'is-agent'"
        >
          <view class="pb-cs__bubble">
            <text class="pb-cs__text">{{ m.content }}</text>
          </view>
          <text class="pb-cs__at pb-ltr">{{ stampOf(m.createdAt) }}</text>
        </view>
        <view v-if="closed" class="pb-cs__hint">{{ $t("cs.closed") }}</view>
      </template>
    </scroll-view>

    <view v-if="session && !closed" class="pb-cs__bar">
      <input
        v-model="draft"
        class="pb-cs__input"
        :placeholder="$t('cs.placeholder')"
        confirm-type="send"
        @confirm="send"
      />
      <pb-button size="sm" :disabled="!draft.trim() || sending" @click="send">{{ $t("cs.send") }}</pb-button>
    </view>
  </pb-scaffold>
</template>

<style scoped>
.pb-cs__list { padding: 24rpx 32rpx; box-sizing: border-box; }
.pb-cs__hint { text-align: center; font-size: 24rpx; color: var(--pb-sub); padding: 24rpx 0; }
.pb-cs__row { display: flex; flex-direction: column; margin-bottom: 24rpx; }
.pb-cs__row.is-me { align-items: flex-end; }
.pb-cs__row.is-agent { align-items: flex-start; }
.pb-cs__bubble {
  max-width: 78%;
  padding: 20rpx 24rpx;
  border-radius: 24rpx;
  background: var(--pb-surface);
  box-shadow: var(--pb-shadow-sm);
}
/* 自己说的用主色底：一眼分得清谁说的，比左右对齐更稳（阿语下左右会整体镜像） */
.pb-cs__row.is-me .pb-cs__bubble { background: var(--pb-primary); }
.pb-cs__row.is-me .pb-cs__text { color: var(--pb-on-primary); }
.pb-cs__text { font-size: 28rpx; line-height: 1.5; color: var(--pb-ink); }
.pb-cs__at { margin-top: 8rpx; font-size: 20rpx; color: var(--pb-sub); }
.pb-cs__bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 16rpx 32rpx;
  padding-bottom: calc(16rpx + constant(safe-area-inset-bottom));
  padding-bottom: calc(16rpx + env(safe-area-inset-bottom));
  background: var(--pb-surface);
  box-shadow: 0 -6rpx 28rpx rgba(18, 20, 34, 0.06);
}
.pb-cs__input {
  flex: 1;
  height: 72rpx;
  padding: 0 24rpx;
  border-radius: 9999px;
  background: var(--pb-faint);
  font-size: 28rpx;
  color: var(--pb-ink);
}
</style>
