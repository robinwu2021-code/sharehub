// 扫码端能力：App 自有扫码页（pages/scan）/ 小程序微信扫一扫。解析柜机号。
//
// reject 的 message：含 "cancel" = 用户返回（调用方静默）；"manual" = 用户点了「输入编号」（调用方打开输入框）。
export function scanCabinet(): Promise<string> {
  // #ifdef APP-PLUS
  // 不用 uni.scanCode：它的扫码界面语言跟系统走、不跟 App 走，也没有输入编号的兜底（见 pages/scan）
  return new Promise((resolve, reject) => {
    uni.navigateTo({
      url: "/pages/scan/index",
      events: {
        result: (raw: string) => resolve(parseCabinetNo(raw)),
        manual: () => reject(new Error("manual")),
        cancel: () => reject(new Error("scan cancelled")),
      },
      fail: (e) => reject(new Error(e.errMsg || "scan page failed")),
    });
  });
  // #endif
  // eslint-disable-next-line no-unreachable
  return new Promise((resolve, reject) => {
    uni.scanCode({
      scanType: ["qrCode"],
      success: (res) => resolve(parseCabinetNo(res.result)),
      fail: (e) => reject(new Error(e.errMsg || "scan cancelled")),
    });
  });
}

/**
 * 本端能不能扫码 —— **按能力探测，不按 UA 判断**。
 *
 * UA 判断每次浏览器更新都可能过期，而下面这三样才是真正决定能不能跑的东西：
 * 原生解码器、相机、安全上下文（`getUserMedia` 在非 HTTPS/localhost 下直接不可用）。
 *
 * App / 小程序走 `uni.scanCode`；H5 走 `BarcodeDetector` + `getUserMedia`（零依赖）。
 * ⚠️ Safari 目前没有 `BarcodeDetector`，会返回 false —— 调用方据此回落到输入编号，
 * 而不是给一个点了没反应的按钮。
 */
export function canScanHere(): boolean {
  // #ifndef H5
  return true;
  // #endif
  // eslint-disable-next-line no-unreachable
  return (
    typeof window !== "undefined" &&
    "BarcodeDetector" in window &&
    !!navigator.mediaDevices?.getUserMedia &&
    window.isSecureContext
  );
}

/** H5 取景层用得到，故导出。二维码可能是带 cabinetNo 的 URL，或直接柜机号 */
export function parseCabinetNo(raw: string): string {
  const m = raw.match(/cabinetNo=([^&]+)/);
  if (m) return decodeURIComponent(m[1]);
  return raw.trim();
}
