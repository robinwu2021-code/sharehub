<script setup lang="ts">
/**
 * App 扫码页（原生取景 + 自己的标题栏）。
 *
 * 此前 App 走 `uni.scanCode`：那是 DCloud 自带的整页扫码界面，标题、按钮文字跟着**系统语言**，
 * 不跟着 App 里选的语言 —— 选了阿语，扫码页却是中文（真机实测），而且也没有「输入编号」这条兜底。
 * 它没有任何参数能改这些。
 *
 * 改为 `plus.barcode.create` 把原生取景框嵌进本页：标题栏、提示、手电筒、输入编号都是自己的，走 i18n / 换肤 / RTL。
 * 原生视图盖在 WebView 之上，所以取景区只放一个占位块，量出它的位置再把原生视图摆上去。
 *
 * 与打开方通过 eventChannel 交互（见 ports/scan.ts）：`result`（原始串）/ `manual`（改用输入编号）/ `cancel`。
 * 只在 App 使用；小程序仍走微信扫一扫，H5 走 pb-scanner。
 */
import { ref, getCurrentInstance, nextTick } from "vue";
import { onReady, onUnload, onHide, onShow } from "@dcloudio/uni-app";
import { MP_ICON } from "@/design/tokens";

const PANEL_RPX = 320;
const areaH = ref(0);
const torch = ref(false);
const denied = ref(false);

let barcode: any = null;
let settled = false;
const channel = (getCurrentInstance()?.proxy as any)?.getOpenerEventChannel?.();

/** 只回一次：离开页面时没回过就算取消 */
function settle(event: "result" | "manual" | "cancel", payload?: string) {
  if (settled) return;
  settled = true;
  channel?.emit(event, payload);
}

let leaving = false;
/** 先退回、退完再通知：否则打开方在返回动画途中 navigateTo 确认页，两次跳转会打架 */
function leave(event: "result" | "manual", payload?: string) {
  if (leaving) return;
  leaving = true;
  uni.navigateBack({
    success: () => settle(event, payload),
    fail: () => settle(event, payload),
  });
}

// #ifdef APP-PLUS
function ensureCamera(): Promise<boolean> {
  return new Promise((resolve) => {
    const p = (globalThis as any).plus;
    if (p?.os?.name !== "Android") return resolve(true); // iOS 由系统在首次取景时询问
    p.android.requestPermissions(
      ["android.permission.CAMERA"],
      (r: { granted: string[] }) => resolve(r.granted.length > 0),
      () => resolve(false),
    );
  });
}

function mount() {
  uni.createSelectorQuery()
    .select("#pbScanArea")
    .boundingClientRect((rect) => {
      const r = rect as UniApp.NodeInfo;
      if (!r || !r.height) return;
      const p = (globalThis as any).plus;
      barcode = p.barcode.create("pbScan", [p.barcode.QR], {
        top: `${r.top}px`,
        left: `${r.left}px`,
        width: `${r.width}px`,
        height: `${r.height}px`,
        position: "static",
        background: "#000000",
        // frameColor / scanbarColor 在本离线 SDK 里不生效（create 与 setStyles 都试过，仍是默认红框；
        // 且 append 之前调 setStyles 会抛、整个取景框出不来）—— 不再尝试，保留默认
      });
      barcode.onmarked = (_type: number, code: string) => {
        barcode?.cancel();
        leave("result", code);
      };
      barcode.onerror = () => {
        denied.value = true;
      };
      const pages = getCurrentPages();
      (pages[pages.length - 1] as any).$getAppWebview().append(barcode);
      barcode.start();
    })
    .exec();
}
// #endif

onReady(async () => {
  const sys = uni.getSystemInfoSync();
  // 屏高 − 状态栏 − 标题栏（88rpx）− 底部操作区 − 手势条安全区
  const safeBottom = sys.safeAreaInsets?.bottom ?? 0;
  areaH.value = Math.max(
    200,
    sys.windowHeight - (sys.statusBarHeight ?? 0) - uni.upx2px(88) - uni.upx2px(PANEL_RPX) - safeBottom,
  );
  // #ifdef APP-PLUS
  if (!(await ensureCamera())) {
    denied.value = true;
    return;
  }
  await nextTick();
  mount();
  // #endif
});

onHide(() => barcode?.cancel());
onShow(() => barcode?.start());
onUnload(() => {
  barcode?.close();
  barcode = null;
  if (!leaving) settle("cancel");
});

function toggleTorch() {
  torch.value = !torch.value;
  barcode?.setFlash(torch.value);
}
</script>

<template>
  <pb-scaffold :title="$t('home.scan')" show-back>
    <view id="pbScanArea" class="pb-scan__area" :style="{ height: areaH + 'px' }">
      <view v-if="denied" class="pb-scan__denied">
        <pb-icon name="scan" :size="64" :color="MP_ICON.onPrimary" />
        <text class="pb-scan__denied-txt">{{ $t("home.cameraDenied") }}</text>
      </view>
    </view>
    <view class="pb-scan__panel" :style="{ height: PANEL_RPX + 'rpx' }">
      <text class="pb-scan__tip">{{ $t("home.scanTip") }}</text>
      <view class="pb-scan__actions">
        <view v-if="!denied" class="pb-scan__btn" :class="{ 'is-on': torch }" @tap="toggleTorch">
          <view class="pb-scan__btn-ico"><pb-icon name="zap" :size="40" :color="torch ? MP_ICON.onPrimary : undefined" /></view>
          <text class="pb-scan__btn-txt">{{ $t("home.torch") }}</text>
        </view>
        <view class="pb-scan__btn" @tap="leave('manual')">
          <view class="pb-scan__btn-ico"><pb-icon name="keyboard" :size="40" /></view>
          <text class="pb-scan__btn-txt">{{ $t("home.enterCabinet") }}</text>
        </view>
      </view>
    </view>
  </pb-scaffold>
</template>

<style scoped>
.pb-scan__area { background: #000000; display: flex; align-items: center; justify-content: center; }
.pb-scan__denied { display: flex; flex-direction: column; align-items: center; gap: 24rpx; padding: 0 64rpx; }
.pb-scan__denied-txt { color: #ffffff; font-size: 28rpx; text-align: center; line-height: 1.6; }
.pb-scan__panel {
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 32rpx;
  padding: 0 40rpx;
}
.pb-scan__tip { font-size: 26rpx; color: var(--pb-sub); text-align: center; }
.pb-scan__actions { display: flex; gap: 96rpx; }
.pb-scan__btn { display: flex; flex-direction: column; align-items: center; gap: 12rpx; color: var(--pb-ink); }
.pb-scan__btn-ico { width: 96rpx; height: 96rpx; border-radius: 9999px; background: var(--pb-faint); display: flex; align-items: center; justify-content: center; }
.pb-scan__btn.is-on .pb-scan__btn-ico { background: var(--pb-primary); color: var(--pb-on-primary); }
.pb-scan__btn-txt { font-size: 24rpx; }
</style>
