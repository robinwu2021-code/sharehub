// 地图/导航端能力：App Google Maps / 小程序腾讯·微信地图（uni.openLocation 唤起原生导航）。
import type { NearbyCabinet } from "@/types";

export function openNavigation(c: NearbyCabinet) {
  uni.openLocation({
    latitude: c.lat,
    longitude: c.lng,
    name: c.siteName,
    address: c.address,
    fail: (e) => uni.showToast({ title: e.errMsg || "map unavailable", icon: "none" }),
  });
}
