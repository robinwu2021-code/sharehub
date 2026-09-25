// 地图/导航端能力：App Google Maps / 小程序腾讯·微信地图（uni.openLocation 唤起原生导航）。
import type { NearbyCabinet } from "@/types";

export function openNavigation(c: NearbyCabinet) {
  // 没坐标就不唤起。传 0,0 会把人导到几内亚湾那个点，而那比「导航不可用」更糟 ——
  // 用户会真的跟着走。
  if (c.lat == null || c.lng == null) {
    uni.showToast({ title: "no location for this store", icon: "none" });
    return;
  }
  uni.openLocation({
    latitude: c.lat,
    longitude: c.lng,
    name: c.siteName,
    address: c.address,
    fail: (e) => uni.showToast({ title: e.errMsg || "map unavailable", icon: "none" }),
  });
}
