// 扫码端能力：App uni.scanCode / 小程序微信扫一扫（uni API 统一）。解析柜机号。
export function scanCabinet(): Promise<string> {
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
