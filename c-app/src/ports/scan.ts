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

// 二维码可能是带 cabinetNo 的 URL，或直接柜机号
function parseCabinetNo(raw: string): string {
  const m = raw.match(/cabinetNo=([^&]+)/);
  if (m) return decodeURIComponent(m[1]);
  return raw.trim();
}
