"use client";

// Google 地图：脚本加载 + 地点搜索 / 详情 / 逆地理编码。
//
// ── 为什么从高德换过来（2026-09-26）──────────────────────────────
// 高德**在阿联酋没有地图数据**。实测同一把 key、同一时刻、同一域名取 z=11 的瓦片：
//
//     高德·北京   23,818 字节 · 取样到 120 种颜色   ← 真地图
//     高德·迪拜      179 字节 · 取样到   1 种颜色   ← 纯色空白
//     Google·迪拜 18,186 字节 · 取样到  80 种颜色   ← 真地图
//
// 症状最坏的地方在于**它不报错**：脚本加载成功、AMap 对象在、版权条和标记都画出来了，
// 只有底图是空白的。看上去像「地图坏了」，实际是这家供应商在这里没有数据 ——
// 没有上面那组对照，很容易一路去查密钥、白名单、CSP。
//
// 首批市场是阿联酋，所以运营端一律走 Google。`components/ui/site-map.tsx`
// 本来就在用 Google（设备撒点），这里把它那个加载器提出来共用 ——
// 同一个页面注入两次 Maps JS 会互相打架。
//
// ── 用 Places REST 而不是 Places JS 库 ──────────────────────────
// Places API (v1) 支持 CORS，可以直接 fetch。好处是不必再加载一个 JS 库、
// 也不必用它那套 `AutocompleteService` 的回调风格。
// `sessionToken` 按 Google 的计费口径串起「一次搜索会话」（多次补全 + 一次详情算一次），
// 不传的话每一次按键都单独计费。
import * as React from "react";

const KEY = process.env.NEXT_PUBLIC_GMAPS_KEY ?? "";

/** 默认视野：迪拜（首批市场）。没有坐标时地图落在这里。 */
export const FALLBACK_CENTER = { lat: 25.2048, lng: 55.2708 };

export type LoadResult = { ok: true } | { ok: false; reason: "no-key" | "script-failed" };

declare global {
  interface Window { google?: typeof globalThis.google }
}

let loading: Promise<LoadResult> | null = null;

/**
 * 加载 Maps JS。全局只加载一次；失败时**不抛异常**，回 `{ok:false}` 让调用方降级。
 *
 * 地图出不来不该挡住「建一个站点」这件事 —— 地址与经纬度照样能手填。
 */
export function loadGoogleMaps(): Promise<LoadResult> {
  if (typeof window === "undefined") return Promise.resolve({ ok: false, reason: "no-key" });
  if (!KEY) return Promise.resolve({ ok: false, reason: "no-key" });
  if (window.google?.maps) return Promise.resolve({ ok: true });
  if (loading) return loading;
  loading = new Promise<LoadResult>((resolve) => {
    const cb = "__pb_ops_gmaps_ready";
    (window as unknown as Record<string, unknown>)[cb] = () => {
      delete (window as unknown as Record<string, unknown>)[cb];
      resolve({ ok: true });
    };
    const s = document.createElement("script");
    s.src = `https://maps.googleapis.com/maps/api/js?key=${KEY}&callback=${cb}&language=zh-CN&loading=async`;
    s.async = true;
    // key 无效 / 网络不通 / 域名没放行 → 降级，不是白屏
    s.onerror = () => resolve({ ok: false, reason: "script-failed" });
    document.head.appendChild(s);
  });
  return loading;
}

export const gmapsConfigured = Boolean(KEY);

export function gmapsReasonText(reason: "no-key" | "script-failed"): string {
  return reason === "no-key"
    ? "未配置地图密钥（NEXT_PUBLIC_GMAPS_KEY），可手工填写经纬度"
    : "地图脚本没加载成功（密钥未放行本域名、未启用 Maps JavaScript API，或网络/CSP 拦截），可手工填写经纬度";
}

// ─────────────────────────── 地点搜索 ───────────────────────────

export interface PlaceHit {
  placeId: string;
  /** 地点名，如「The Dubai Mall」 */
  name: string;
  /** 完整地址 —— 存进库的是这个（名字派不了单，地址才能让人找到路）。 */
  address: string;
  /** Google 给的类型中文名，如「购物中心」。可能为空。 */
  typeLabel: string;
  lat: number;
  lng: number;
}

/** 可按类型筛的地点类型（Places「Table A」primary type）。全部实测被接受。 */
export const PLACE_TYPES: { value: string; label: string; keyword: string }[] = [
  { value: "shopping_mall", label: "购物中心", keyword: "shopping mall" },
  { value: "airport", label: "机场", keyword: "airport" },
  { value: "transit_station", label: "交通枢纽", keyword: "metro station" },
  { value: "hotel", label: "酒店", keyword: "hotel" },
  { value: "restaurant", label: "餐饮", keyword: "restaurant" },
  { value: "supermarket", label: "零售", keyword: "supermarket" },
  { value: "corporate_office", label: "办公楼", keyword: "office tower" },
  { value: "hospital", label: "医疗", keyword: "hospital" },
  { value: "school", label: "学校", keyword: "school" },
  { value: "tourist_attraction", label: "景区", keyword: "attraction" },
];

export interface SearchOpts {
  /** Places primary type；空 = 不限。 */
  type?: string;
  /** 地理偏置中心：优先用已选坐标，否则首批市场中心。 */
  bias?: { lat: number; lng: number };
  /** 只搜这个矩形范围内的（「只搜当前地图范围」用）。给了就覆盖 bias。 */
  bounds?: { south: number; west: number; north: number; east: number } | null;
}

/**
 * 地点搜索。
 *
 * <h3>用 searchText 而不是 autocomplete</h3>
 * 这两个 API 都能做「边打边找」，但 **searchText 一次就把坐标带回来**，
 * autocomplete 只给 placeId、要为**每一条**再调一次详情。
 * 而「把候选撒到地图上让人先看看再选」恰恰需要每条都有坐标 ——
 * 走 autocomplete 的话那是 N 次详情调用（N 倍计费）。
 * 换过来之后顺带没了 sessionToken 那套（searchText 不用它），选中也不再需要二次请求。
 *
 * <h3>为什么要偏置</h3>
 * 不给中心点的话「Marina」会优先命中别的国家。
 *
 * 任何失败都回空表而不是抛错：搜不到东西不该让输入框崩掉，手填那条路一直在。
 */
export async function searchPlaces(text: string, opts: SearchOpts = {}): Promise<PlaceHit[]> {
  const q = text.trim();
  if (!KEY || !q) return [];
  const bias = opts.bias ?? FALLBACK_CENTER;
  try {
    const r = await fetch("https://places.googleapis.com/v1/places:searchText", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": KEY,
        // FieldMask 必填，且**直接决定计费档位** —— 只要真正用得上的字段
        "X-Goog-FieldMask":
          "places.id,places.displayName,places.formattedAddress,places.location,places.primaryTypeDisplayName",
      },
      body: JSON.stringify({
        textQuery: q,
        languageCode: "zh-CN",
        ...(opts.type ? { includedType: opts.type } : {}),
        ...(opts.bounds
          ? {
              locationRestriction: {
                rectangle: {
                  low: { latitude: opts.bounds.south, longitude: opts.bounds.west },
                  high: { latitude: opts.bounds.north, longitude: opts.bounds.east },
                },
              },
            }
          : { locationBias: { circle: { center: { latitude: bias.lat, longitude: bias.lng }, radius: 50000 } } }),
      }),
    });
    const j = await r.json();
    return (j.places ?? [])
      .filter((p: Record<string, any>) => p?.location)   // eslint-disable-line @typescript-eslint/no-explicit-any
      .map((p: Record<string, any>) => ({                // eslint-disable-line @typescript-eslint/no-explicit-any
        placeId: p.id,
        name: p.displayName?.text ?? "",
        address: p.formattedAddress ?? "",
        typeLabel: p.primaryTypeDisplayName?.text ?? "",
        lat: Number(p.location.latitude),
        lng: Number(p.location.longitude),
      }));
  } catch {
    return [];
  }
}

/** 坐标 → 地址。查不到回 null —— **不要清空**用户已填的地址。 */
export async function reverseGeocode(lat: number, lng: number): Promise<string | null> {
  if (!KEY) return null;
  try {
    const r = await fetch(
      `https://maps.googleapis.com/maps/api/geocode/json?latlng=${lat},${lng}&language=zh-CN&key=${KEY}`);
    const j = await r.json();
    return j?.results?.[0]?.formatted_address ?? null;
  } catch {
    return null;
  }
}

/** 输入防抖：搜索按键触发，不防抖的话每敲一个字母都是一次计费请求。 */
export function useDebounced<T>(value: T, ms = 300): T {
  const [v, setV] = React.useState(value);
  React.useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}
