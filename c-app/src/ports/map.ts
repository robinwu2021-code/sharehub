// 地图/导航端能力：App Google Maps / 小程序腾讯·微信地图（uni.openLocation 唤起原生导航）。
import type { NearbyCabinet } from "@/types";

export function openNavigation(c: NearbyCabinet) {
  // 没坐标就不唤起。传 0,0 会把人导到几内亚湾那个点，而那比「导航不可用」更糟 ——
  // 用户会真的跟着走。
  if (c.lat == null || c.lng == null) {
    uni.showToast({ title: "no location for this store", icon: "none" });
    return;
  }
  // #ifdef MP
  // 小程序：微信内置地图页本身就有「导航」按钮，体验是好的
  uni.openLocation({
    latitude: c.lat,
    longitude: c.lng,
    name: c.siteName,
    address: c.address,
    fail: (e) => uni.showToast({ title: e.errMsg || "map unavailable", icon: "none" }),
  });
  // #endif
  // #ifndef MP
  /*
   * App / H5：**直接唤起 Google Maps 导航**，不走 uni.openLocation。
   *
   * App 里 openLocation 会先打开一个 uni 内置的位置页，那一页的预览地图要地图模块才画得出来
   * （离线包没集成），于是用户看到一整页空白，要再点右下角的按钮才进 Google Maps。
   * Android 模拟器实测如此。Google 的 Maps URL 在装了 Google Maps 的手机上直接进导航，
   * 没装就开网页版 —— 两种都比空白页强。
   */
  const url = `https://www.google.com/maps/dir/?api=1&destination=${c.lat},${c.lng}`;
  // #ifdef APP-PLUS
  plus.runtime.openURL(url, () => uni.showToast({ title: "map unavailable", icon: "none" }));
  // #endif
  // #ifdef H5
  window.open(url, "_blank");
  // #endif
  // #endif
}
