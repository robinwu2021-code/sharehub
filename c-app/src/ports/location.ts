// 定位端能力：H5 走 navigator.geolocation，小程序/App 走 uni.getLocation。
//
// **拿不到就返回 null，绝不编一个坐标顶上。** 这件事上「合理的默认值」是有害的：
// 用一个假原点算出来的距离看起来完全正常（几百米、几公里，排序也像模像样），
// 而它是错的 —— 用户会按着那个排序去最近的店，然后发现不是。
// 后端那边同样的道理：算不出的 distanceM 回 null 不回 0。
//
// 坐标系用 **WGS84**（GPS 原始值）：`loc_site.lat/lng` 存的是 WGS84，
// Google Maps 也吃 WGS84。微信默认给 gcj02（国测局偏移），在国内用是对的、
// 在阿联酋会偏出去几百米 —— 本项目主场是中东，显式要 wgs84。

export interface Coords {
  lat: number;
  lng: number;
}

/** 缓存一分钟：首页与收藏页各拉一次列表，不该弹两次授权。 */
const TTL_MS = 60_000;
/** 定位最多等这么久（与 H5 分支的 timeout 同口径）。拿不到位置不该让整页等着。 */
const LOCATE_TIMEOUT_MS = 5000;
let cached: { at: number; value: Coords | null } | null = null;
let inflight: Promise<Coords | null> | null = null;

export function getUserLocation(): Promise<Coords | null> {
  if (cached && Date.now() - cached.at < TTL_MS) return Promise.resolve(cached.value);
  // 并发去重：两个页面同时起来时只问一次
  if (inflight) return inflight;
  inflight = locate()
    .then((v) => {
      cached = { at: Date.now(), value: v };
      return v;
    })
    .finally(() => {
      inflight = null;
    });
  return inflight;
}

/** 用户改了授权之后可以强制重来（设置页里的「重新定位」之类）。 */
export function clearLocationCache(): void {
  cached = null;
}

function locate(): Promise<Coords | null> {
  // #ifdef H5
  return new Promise((resolve) => {
    if (typeof navigator === "undefined" || !navigator.geolocation) return resolve(null);
    navigator.geolocation.getCurrentPosition(
      (pos) => resolve({ lat: pos.coords.latitude, lng: pos.coords.longitude }),
      () => resolve(null), // 拒绝授权 / 超时 —— 是「不知道」，不是「在迪拜市中心」
      { timeout: 5000, maximumAge: 60_000 },
    );
  });
  // #endif
  // #ifndef H5
  return new Promise((resolve) => {
    /*
     * **必须有超时。** uni.getLocation 在 App 端可能既不 success 也不 fail —— 模拟器只推过一次坐标、
     * 真机在室内弱 GPS 下都会这样。而上面的 inflight 是全局共享的：这一次挂住，
     * **之后所有取定位的调用都跟着永远挂住**，首页 load() 永不返回（整页空白）、
     * 搜索永不生效（请求根本没发出去）。Android 模拟器实测：一次搜索等了一分多钟请求都没发。
     * 超时就按「不知道位置」处理（null），与拒绝授权同一条路 —— 列表照出，只是不带距离。
     */
    let settled = false;
    const done = (v: Coords | null) => {
      if (settled) return;
      settled = true;
      resolve(v);
    };
    setTimeout(() => done(null), LOCATE_TIMEOUT_MS);
    uni.getLocation({
      type: "wgs84",
      success: (r) => done({ lat: r.latitude, lng: r.longitude }),
      fail: () => done(null),
    });
  });
  // #endif
}
