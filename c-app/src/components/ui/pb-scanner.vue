<script setup lang="ts">
/**
 * H5 取景扫码层（`BarcodeDetector` + `getUserMedia`，零依赖）。
 *
 * 只在浏览器真的具备这两样能力时才由调用方打开 —— 探测见 `ports/scan.ts#canScanHere`。
 * App / 小程序走 `uni.scanCode`，不用本组件。
 */
import { ref, watch, onUnmounted } from "vue";
import { parseCabinetNo } from "@/ports/scan";

const props = defineProps<{ visible: boolean }>();
const emit = defineEmits<{
  (e: "update:visible", v: boolean): void;
  (e: "result", cabinetNo: string): void;
  /** 「扫不出来，改用输入」—— 取景层里常驻这个出口，不该只能退出重来 */
  (e: "manual"): void;
}>();

const video = ref<HTMLVideoElement | null>(null);
const hint = ref("");
let stream: MediaStream | null = null;
let raf = 0;

/**
 * 关掉相机。**必须显式停轨道** —— 只把元素藏起来的话相机指示灯一直亮着，
 * 用户会认为应用在偷拍，这是会被直接卸载的那类观感问题。
 */
function stop() {
  cancelAnimationFrame(raf);
  raf = 0;
  stream?.getTracks().forEach((t) => t.stop());
  stream = null;
  if (video.value) video.value.srcObject = null;
}

function close() {
  stop();
  emit("update:visible", false);
}

async function start() {
  hint.value = "";
  try {
    // facingMode environment = 后置摄像头；桌面浏览器没有后置会自动落到默认那颗
    stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: "environment" } },
      audio: false,
    });
  } catch {
    // 拒绝授权是正当选择，不当错误报 —— 给一句话 + 手输出口
    hint.value = "camera_denied";
    return;
  }
  const el = video.value;
  if (!el) return;
  el.srcObject = stream;
  await el.play().catch(() => undefined);

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const detector = new (window as any).BarcodeDetector({ formats: ["qr_code"] });
  const tick = async () => {
    if (!props.visible || !video.value) return;
    try {
      const codes = await detector.detect(video.value);
      if (codes?.length) {
        const raw = String(codes[0].rawValue || "").trim();
        if (raw) {
          stop();
          emit("update:visible", false);
          emit("result", parseCabinetNo(raw));
          return;
        }
      }
    } catch {
      // 单帧解码失败很常见（画面模糊/无码），继续下一帧即可，不打断
    }
    raf = requestAnimationFrame(() => void tick());
  };
  raf = requestAnimationFrame(() => void tick());
}

watch(
  () => props.visible,
  (v) => (v ? void start() : stop()),
);
onUnmounted(stop);
</script>

<template>
  <view v-if="visible" class="pb-scan">
    <video ref="video" class="pb-scan__video" playsinline muted autoplay />
    <view class="pb-scan__mask">
      <view class="pb-scan__frame" />
      <text class="pb-scan__tip">
        {{ hint === "camera_denied" ? $t("home.cameraDenied") : $t("home.scanTip") }}
      </text>
    </view>
    <view class="pb-scan__bar">
      <view class="pb-scan__btn" @tap="close">{{ $t("common.cancel") }}</view>
      <view class="pb-scan__btn is-primary" @tap="close(); emit('manual')">
        {{ $t("home.enterCabinet") }}
      </view>
    </view>
  </view>
</template>

<style scoped>
.pb-scan { position: fixed; inset: 0; z-index: 200; background: #000; }
.pb-scan__video { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.pb-scan__mask {
  position: absolute; inset: 0; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 32rpx;
}
/* 取景框：只给四角与一圈淡边，不压住画面 */
.pb-scan__frame {
  width: 440rpx; height: 440rpx; border-radius: 40rpx;
  border: 4rpx solid rgba(255, 255, 255, 0.9);
  box-shadow: 0 0 0 9999rpx rgba(0, 0, 0, 0.45);
}
.pb-scan__tip { color: #fff; font-size: 26rpx; text-align: center; padding: 0 60rpx; }
.pb-scan__bar {
  position: absolute; left: 0; right: 0; display: flex; gap: 20rpx; padding: 0 48rpx;
  bottom: calc(80rpx + constant(safe-area-inset-bottom));
  bottom: calc(80rpx + env(safe-area-inset-bottom));
}
.pb-scan__btn {
  flex: 1; text-align: center; padding: 26rpx 0; border-radius: 9999px;
  font-size: 29rpx; font-weight: 700;
  background: rgba(255, 255, 255, 0.18); color: #fff;
}
.pb-scan__btn.is-primary { background: #fff; color: #111; }
</style>
