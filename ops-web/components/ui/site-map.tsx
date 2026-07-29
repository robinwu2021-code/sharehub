"use client";

// 站点地图撒点（G4 设备地图分布）。
//
// 设计取舍：
// 1. **按站点聚合，不逐台机柜撒点** —— 机柜上千台时逐个 marker 会卡死浏览器；
//    站点是天然聚合单位，marker 上直接标该站的机柜数。
// 2. **有 key 加载真实 Google Maps，无 key 降级为列表占位** —— 沿用 c-app 的
//    `pb-map.vue` 模式。运营端不该因为没配 key 就白屏或报错。
// 3. 不引第三方 React 地图库：`output: export` 静态导出下，直接用官方 JS API
//    最省依赖；组件卸载时清理 marker，避免切 tab 泄漏。
import * as React from "react";
import { Card } from "./card";
import { EmptyState } from "./misc";
import { Badge } from "./badge";

export interface MapPoint {
  id: string;
  name: string;
  lat: number;
  lng: number;
  /** 主指标（如机柜数），显示在 marker 标签与信息窗 */
  count?: number;
  /** 次要说明（如区域名/场景） */
  desc?: string;
  /** 异常态：marker 转红（如站点停用、离线率高） */
  alert?: boolean;
}

const KEY = process.env.NEXT_PUBLIC_GMAPS_KEY;
/** 迪拜市中心（无点位时的默认视野中心） */
const FALLBACK_CENTER = { lat: 25.2048, lng: 55.2708 };

declare global {
  interface Window { google?: typeof globalThis.google }
}

let loading: Promise<void> | null = null;
/** 全局只加载一次脚本；失败时 resolve（静默降级），不让地图挂掉整个页面。 */
function loadGoogleMaps(): Promise<void> {
  if (typeof window === "undefined" || !KEY) return Promise.resolve();
  if (window.google?.maps) return Promise.resolve();
  if (loading) return loading;
  loading = new Promise<void>((resolve) => {
    const cb = "__pb_ops_gmaps_ready";
    (window as unknown as Record<string, unknown>)[cb] = () => {
      delete (window as unknown as Record<string, unknown>)[cb];
      resolve();
    };
    const s = document.createElement("script");
    s.src = `https://maps.googleapis.com/maps/api/js?key=${KEY}&callback=${cb}&language=zh-CN&loading=async`;
    s.async = true;
    s.onerror = () => resolve(); // key 无效/网络不通 → 降级占位
    document.head.appendChild(s);
  });
  return loading;
}

export function SiteMap({
  points, height = 460, onSelect,
}: {
  points: MapPoint[];
  height?: number;
  onSelect?: (p: MapPoint) => void;
}) {
  const ref = React.useRef<HTMLDivElement>(null);
  const mapRef = React.useRef<google.maps.Map | null>(null);
  const markersRef = React.useRef<google.maps.Marker[]>([]);
  const [ready, setReady] = React.useState(false);
  const [failed, setFailed] = React.useState(!KEY);

  React.useEffect(() => {
    let alive = true;
    loadGoogleMaps().then(() => {
      if (!alive) return;
      if (!window.google?.maps) { setFailed(true); return; }
      setReady(true);
    });
    return () => { alive = false; };
  }, []);

  // 撒点：points 变化时全量重建 marker（数量是站点级，几十个，重建成本可忽略）
  React.useEffect(() => {
    if (!ready || !ref.current || !window.google?.maps) return;
    const g = window.google.maps;
    if (!mapRef.current) {
      mapRef.current = new g.Map(ref.current, {
        center: points[0] ? { lat: points[0].lat, lng: points[0].lng } : FALLBACK_CENTER,
        zoom: 11,
        mapTypeControl: false,
        streetViewControl: false,
        fullscreenControl: false,
      });
    }
    const map = mapRef.current;
    markersRef.current.forEach((m) => m.setMap(null));
    markersRef.current = [];

    const bounds = new g.LatLngBounds();
    for (const p of points) {
      const marker = new g.Marker({
        position: { lat: p.lat, lng: p.lng },
        map,
        title: `${p.name}${p.desc ? ` · ${p.desc}` : ""}`,
        label: p.count != null ? { text: String(p.count), color: "#fff", fontSize: "11px", fontWeight: "600" } : undefined,
        icon: {
          path: g.SymbolPath.CIRCLE,
          fillColor: p.alert ? "#ef4444" : "#2563eb",
          fillOpacity: 1,
          strokeColor: "#ffffff",
          strokeWeight: 2,
          scale: 14,
        },
      });
      if (onSelect) marker.addListener("click", () => onSelect(p));
      markersRef.current.push(marker);
      bounds.extend({ lat: p.lat, lng: p.lng });
    }
    const applyView = () => {
      if (points.length > 1) map.fitBounds(bounds, 48);
      else if (points.length === 1) { map.setCenter({ lat: points[0].lat, lng: points[0].lng }); map.setZoom(14); }
    };
    applyView();
    // ⚠️ 地图常在容器最终尺寸确定前就初始化（首帧布局 / tab 切换），Google 会按当时的
    // 尺寸算视口，结果瓦片只铺满一小块。ResizeObserver 只在尺寸**变化**时触发，
    // 而这里尺寸自始至终没变过 → 救不到。所以创建后主动补一次 resize + 重设视野。
    const raf = requestAnimationFrame(() => {
      g.event.trigger(map, "resize");
      applyView();
    });
    return () => cancelAnimationFrame(raf);
  }, [ready, points, onSelect]);

  // 容器尺寸变化后要让地图重算视野：地图常在容器最终尺寸确定前就初始化了
  // （tab 切换 / 面板展开 / 首帧布局），不重算的话瓦片只渲染初始化那一小块。
  React.useEffect(() => {
    if (!ready || !ref.current || !mapRef.current || !window.google?.maps) return;
    const el = ref.current;
    const ro = new ResizeObserver(() => {
      const map = mapRef.current;
      if (!map) return;
      const c = map.getCenter();
      window.google!.maps.event.trigger(map, "resize");
      if (c) map.setCenter(c); // resize 会丢中心，补回
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, [ready]);

  // 卸载时清 marker，防止切 tab 累积
  React.useEffect(() => () => {
    markersRef.current.forEach((m) => m.setMap(null));
    markersRef.current = [];
    mapRef.current = null;
  }, []);

  if (failed) {
    return (
      <Card className="p-4">
        <EmptyState
          title={KEY ? "地图加载失败" : "未配置地图 Key，暂以列表展示"}
          desc={KEY
            ? "请检查网络，或确认该 Key 已启用 Maps JavaScript API 并放行了当前域名"
            : "在 ops-web/.env.local 配置 NEXT_PUBLIC_GMAPS_KEY 后即可看到地图撒点"}
        />
        <div className="mt-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
          {points.map((p) => (
            <button
              key={p.id}
              type="button"
              onClick={() => onSelect?.(p)}
              className="flex items-center justify-between rounded-lg bg-secondary px-3 py-2 text-start text-sm transition-colors hover:bg-accent"
            >
              <span className="truncate">
                <span className="font-medium">{p.name}</span>
                {p.desc && <span className="ms-1.5 text-muted-foreground">{p.desc}</span>}
              </span>
              {p.count != null && <Badge tone={p.alert ? "danger" : "outline"}>{p.count}</Badge>}
            </button>
          ))}
        </div>
      </Card>
    );
  }

  return (
    <Card className="overflow-hidden">
      <div ref={ref} style={{ height }} className="w-full bg-secondary" />
    </Card>
  );
}
