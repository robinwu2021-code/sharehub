<script setup lang="ts">
/*
 * 公告与站内信 —— 两件不同的东西，所以分两栏：
 *
 * - **公告**是广播（`/mp/notice`），三语三列全量下发、由端按当前语种取，**没有已读态**；
 * - **站内信**是每人一份（`/mp/user/messages`），有已读态，点开即标已读。
 *
 * 此前这一页只有公告，而且前端类型写成了 `{title, body, date, read}` ——
 * 四个字段后端一个都不返（后端是 `title/titleEn/titleAr` + `content/...` + `createdAt`）。
 * 表现是公告整页空白，而那个未读红点**永远亮着**，因为 `read` 恒为 undefined。
 */
import { ref, computed } from "vue";
import { onShow, onLoad } from "@dcloudio/uni-app";
import { useUserStore } from "@/stores/user";
import {
  noticeTitle as noticeTitleOf,
  noticeBody as noticeBodyOf,
  messageTitle as messageTitleOf,
  messageBody as messageBodyOf,
} from "@/shared/notice";
import { useI18n } from "vue-i18n";
import { useAppStore } from "@/stores/app";
import { api } from "@/api";
import type { Notice, MessageItem } from "@/types";

const { t } = useI18n();
const app = useAppStore();
/**
 * 默认页签：从首页公告条进来（?tab=notices）或游客，停在「公告」。
 * 游客没有站内信，默认停在收件箱只能看到「暂无消息」；从公告条点进来的人要看的也是那条公告。
 */
const tab = ref(useUserStore().isLoggedIn ? "messages" : "notices");
onLoad((q) => {
  if (q?.tab === "notices") tab.value = "notices";
});
const notices = ref<Notice[]>([]);
const messages = ref<MessageItem[]>([]);
const loading = ref(true);
const busy = ref("");

const options = computed(() => [
  { label: t("notice.messages"), value: "messages" },
  { label: t("notice.notices"), value: "notices" },
]);

// 公告按当前语种取列。后端三语全下发正是为了让端上这一步自己挑，
// 少了这一步就会出现「界面是阿语、公告是中文」。
// 挑语言的规则与首页公告条共用一份（shared/notice.ts）
const noticeTitle = (n: Notice) => noticeTitleOf(n, app.lang);
const noticeBody = (n: Notice) => noticeBodyOf(n, app.lang);

// 站内信也是三语三列（V133），规则与公告共用同一份 pick，否则两处迟早分叉
const messageTitle = (m: MessageItem) => messageTitleOf(m, app.lang);
const messageBody = (m: MessageItem) => messageBodyOf(m, app.lang);

const unread = computed(() => messages.value.filter((m) => !m.read).length);

async function load() {
  loading.value = true;
  try {
    // 公告是广播、游客可看；站内信是个人的、要登录。此前两者绑在一个 Promise.all 里：
    // 游客的站内信 401 → 整个 all 失败 → **连公告也不显示**，从首页公告条点进来是一页空白。
    const loggedIn = useUserStore().isLoggedIn;
    const [ns, ms] = await Promise.all([
      api.listNotices(),
      loggedIn ? api.listMessages({ size: 50 }) : Promise.resolve({ list: [] as MessageItem[], total: 0 }),
    ]);
    notices.value = ns;
    messages.value = ms.list;
  } finally {
    loading.value = false;
  }
}
onShow(load);

async function open(m: MessageItem) {
  if (m.read || busy.value) return;
  busy.value = m.messageNo;
  try {
    const updated = await api.markMessageRead(m.messageNo);
    Object.assign(m, updated); // 以后端返回的为准，不在前端假设已读时间
  } catch {
    // 标已读失败就让红点留着 —— 假装已读会让用户以为看过了
  } finally {
    busy.value = "";
  }
}
</script>

<template>
  <pb-scaffold :title="$t('notice.title')" show-back>
    <view class="px-[32rpx] pt-[24rpx]">
      <pb-segmented v-model="tab" :options="options" />
    </view>

    <!-- 站内信 -->
    <view v-if="tab === 'messages'" class="flex flex-col gap-[20rpx] px-[32rpx] pt-[24rpx]">
      <view v-if="unread" class="text-[22rpx] text-sub">{{ $t("notice.unread", { n: unread }) }}</view>
      <pb-card
        v-for="(m, i) in messages"
        :key="m.messageNo"
        :flat="m.read"
        class="pb-rise"
        :style="{ animationDelay: i * 50 + 'ms' }"
        @tap="open(m)"
      >
        <view class="flex items-center justify-between">
          <text class="text-[30rpx] font-bold text-ink">{{ messageTitle(m) }}</text>
          <view v-if="!m.read" class="pb-dot-red" />
        </view>
        <text class="mt-[12rpx] block text-[26rpx] text-sub" style="line-height: 1.6">{{ messageBody(m) }}</text>
        <text class="mt-[14rpx] block text-[22rpx] text-sub">{{ m.createdAt }}</text>
      </pb-card>
      <pb-empty v-if="!loading && !messages.length" icon="bell" :text="$t('notice.noMessages')" />
    </view>

    <!-- 公告：没有已读态，所以既没有红点，也不可点 -->
    <view v-else class="flex flex-col gap-[20rpx] px-[32rpx] pt-[24rpx]">
      <pb-card v-for="(n, i) in notices" :key="n.noticeNo" class="pb-rise" :style="{ animationDelay: i * 50 + 'ms' }">
        <view class="flex items-center gap-[12rpx]">
          <pb-tag v-if="n.pinned" type="primary">{{ $t("notice.pinned") }}</pb-tag>
          <text class="text-[30rpx] font-bold text-ink">{{ noticeTitle(n) }}</text>
        </view>
        <text class="mt-[12rpx] block text-[26rpx] text-sub" style="line-height: 1.6">{{ noticeBody(n) }}</text>
        <text class="mt-[14rpx] block text-[22rpx] text-sub">{{ n.createdAt }}</text>
      </pb-card>
      <pb-empty v-if="!loading && !notices.length" icon="bell" :text="$t('notice.noNotices')" />
    </view>
  </pb-scaffold>
</template>

<style scoped>
.pb-dot-red { width: 16rpx; height: 16rpx; border-radius: 9999px; background: var(--pb-danger); }
</style>
