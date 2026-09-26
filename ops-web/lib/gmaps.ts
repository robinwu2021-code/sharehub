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
  /** 主标题，如「Mall of the Emirates」 */
  main: string;
  /** 次要说明，如「Sheikh Zayed Rd - Al Barsha - Dubai」 */
  secondary: string;
}

export interface PlaceDetail {
  address: string;
  lat: number;
  lng: number;
}

/** 一次搜索会话的令牌：多次补全 + 一次详情按一次计费。 */
export function newSessionToken(): string {
  return (crypto.randomUUID?.() ?? String(Math.random()).slice(2)) as string;
}

/**
 * 地点搜索（自动补全）。
 *
 * `bias` 给一个中心点做地理偏置 —— 不给的话「Marina」会优先命中别的国家。
 * 任何失败都回空表而不是抛错：搜不到东西不该让输入框崩掉，手填那条路一直在。
 */
export async function searchPlaces(
  input: string, sessionToken: string, bias = FALLBACK_CENTER,
): Promise<PlaceHit[]> {
  if (!KEY || !input.trim()) return [];
  try {
    const r = await fetch("https://places.googleapis.com/v1/places:autocomplete", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Goog-Api-Key": KEY },
      body: JSON.stringify({
        input: input.trim(),
        sessionToken,
        locationBias: { circle: { center: { latitude: bias.lat, longitude: bias.lng }, radius: 50000 } },
      }),
    });
    const j = await r.json();
    return (j.suggestions ?? [])
      .map((s: Record<string, any>) => s.placePrediction)  // eslint-disable-line @typescript-eslint/no-explicit-any
      .filter(Boolean)
      .map((p: Record<string, any>) => ({                  // eslint-disable-line @typescript-eslint/no-explicit-any
        placeId: p.placeId,
        main: p.structuredFormat?.mainText?.text ?? p.text?.text ?? "",
        secondary: p.structuredFormat?.secondaryText?.text ?? "",
      }))
      .filter((p: PlaceHit) => p.placeId && p.main);
  } catch {
    return [];
  }
}

/** 取地点详情（地址 + 坐标）。查不到回 null，调用方保留用户已填的值。 */
export async function placeDetail(placeId: string, sessionToken: string): Promise<PlaceDetail | null> {
  if (!KEY || !placeId) return null;
  try {
    const r = await fetch(
      `https://places.googleapis.com/v1/places/${encodeURIComponent(placeId)}?sessionToken=${encodeURIComponent(sessionToken)}`,
      { headers: { "X-Goog-Api-Key": KEY, "X-Goog-FieldMask": "formattedAddress,location,displayName" } },
    );
    const j = await r.json();
    if (!j?.location) return null;
    return {
      // 用 formattedAddress 而不是 displayName：前者是完整地址（人照着能找到路），
      // 后者只是名字（「Mall of the Emirates」这五个字派不了单）
      address: j.formattedAddress ?? j.displayName?.text ?? "",
      lat: Number(j.location.latitude),
      lng: Number(j.location.longitude),
    };
  } catch {
    return null;
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
