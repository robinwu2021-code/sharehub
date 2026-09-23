"use client";

// 地址选点：一个地址输入框 +（可选）地图选点 + 经纬度。
//
// 为什么不是三个独立字段：地址与经纬度**必须一致**，分开手填时两者对不上没人拦得住 ——
// C 端「找附近」按经纬度排序、运营端撒点按经纬度落点，地址只是给人看的。
// 所以这里把三个值绑成一个控件：地图上点一下，三个值一起写。
//
// 降级：没配地图密钥（或脚本加载失败）时**不白屏也不报错** —— 地址照样手填，
// 经纬度露出两个小输入框手填，只是没有地图。建站点这件事本身不被地图挡住。
//
// 供应商：目前是高德（见 lib/amap.ts 的边界说明）。将来切 Google 时改这个文件的内部实现，
// 对外的 props（address / lat / lng / onChange）不变。
import * as React from "react";
import { MapPin, LocateFixed } from "lucide-react";
import { Input } from "./input";
import { Button } from "./button";
import { cn } from "@/lib/utils";
import { loadAMap, amapReasonText, type AMapNS } from "@/lib/amap";

export type AddressValue = { address: string; lat: number | ""; lng: number | "" };

/** 默认视野：迪拜（首批市场）。没有坐标时地图落在这里，而不是落在高德默认的北京。 */
const FALLBACK_CENTER: [number, number] = [55.2708, 25.2048];

export function AddressPicker({
  value, onChange, disabled, invalid, onBlur, placeholder,
}: {
  value: AddressValue;
  onChange: (v: AddressValue) => void;
  disabled?: boolean;
  invalid?: boolean;
  onBlur?: () => void;
  placeholder?: string;
}) {
  const [open, setOpen] = React.useState(false);
  const [fail, setFail] = React.useState<string | null>(null);
  const boxRef = React.useRef<HTMLDivElement | null>(null);
  const mapRef = React.useRef<any>(null); // eslint-disable-line @typescript-eslint/no-explicit-any
  const markerRef = React.useRef<any>(null); // eslint-disable-line @typescript-eslint/no-explicit-any
  const geoRef = React.useRef<any>(null); // eslint-disable-line @typescript-eslint/no-explicit-any

  // onChange 会随每次输入变新，但地图事件只在初始化时绑一次 —— 用 ref 取最新的，
  // 否则地图里点一下写回的是「打开地图那一刻」的旧值（闭包陷阱，表现为经纬度写了地址没写）。
  const latest = React.useRef({ value, onChange });
  latest.current = { value, onChange };

  const set = (patch: Partial<AddressValue>) => {
    const cur = latest.current.value;
    latest.current.onChange({ ...cur, ...patch });
  };

  /** 经纬度 → 地址。查不到就只写坐标，不清空用户已填的地址。 */
  const reverse = (lng: number, lat: number) => {
    set({ lng, lat });
    const geo = geoRef.current;
    if (!geo) return;
    geo.getAddress([lng, lat], (status: string, result: any) => { // eslint-disable-line @typescript-eslint/no-explicit-any
      if (status === "complete" && result?.regeocode?.formattedAddress) {
        set({ address: result.regeocode.formattedAddress });
      }
    });
  };

  /** 地址 → 经纬度（点「定位」时）。 */
  const locate = () => {
    const geo = geoRef.current;
    const addr = latest.current.value.address?.trim();
    if (!geo || !addr) return;
    geo.getLocation(addr, (status: string, result: any) => { // eslint-disable-line @typescript-eslint/no-explicit-any
      const p = status === "complete" ? result?.geocodes?.[0]?.location : null;
      if (!p) { setFail("按这个地址没找到坐标，可在地图上直接点选"); return; }
      setFail(null);
      const lng = Number(p.lng), lat = Number(p.lat);
      set({ lng, lat });
      mapRef.current?.setZoomAndCenter(16, [lng, lat]);
      markerRef.current?.setPosition([lng, lat]);
    });
  };

  // 地图初始化：只在第一次展开时做（懒加载脚本）。
  React.useEffect(() => {
    if (!open || mapRef.current) return;
    let dead = false;
    loadAMap().then((r) => {
      if (dead || !boxRef.current) return;
      if (!r.ok) { setFail(amapReasonText(r.reason)); return; }
      const AMap = r.AMap as AMapNS;
      const v = latest.current.value;
      const has = v.lng !== "" && v.lat !== "";
      const center: [number, number] = has ? [Number(v.lng), Number(v.lat)] : FALLBACK_CENTER;
      const map = new AMap.Map(boxRef.current, { zoom: has ? 16 : 11, center });
      const marker = new AMap.Marker({ position: center, draggable: true, map });
      geoRef.current = new AMap.Geocoder();
      map.on("click", (e: any) => { // eslint-disable-line @typescript-eslint/no-explicit-any
        marker.setPosition([e.lnglat.getLng(), e.lnglat.getLat()]);
        reverse(e.lnglat.getLng(), e.lnglat.getLat());
      });
      marker.on("dragend", (e: any) => reverse(e.lnglat.getLng(), e.lnglat.getLat())); // eslint-disable-line @typescript-eslint/no-explicit-any
      mapRef.current = map;
      markerRef.current = marker;
    });
    return () => { dead = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  // 抽屉关闭时销毁地图实例，避免下次打开叠一层（高德不自己清）。
  React.useEffect(() => () => { mapRef.current?.destroy?.(); mapRef.current = null; }, []);

  const num = (s: string) => (s === "" ? "" : Number(s));

  return (
    <div className="space-y-2">
      <div className="flex gap-2">
        <Input
          className={cn("flex-1", invalid && "ring-2 ring-destructive")}
          value={value.address ?? ""}
          disabled={disabled}
          placeholder={placeholder}
          onBlur={onBlur}
          onChange={(e) => set({ address: e.target.value })}
        />
        <Button type="button" variant="outline" size="sm" disabled={disabled} onClick={() => setOpen((o) => !o)}>
          <MapPin className="size-4" />
          {open ? "收起地图" : "地图选点"}
        </Button>
      </div>

      {open && (
        <div className="space-y-2">
          {fail ? (
            <div className="rounded-field bg-secondary px-3 py-2 txt-caption text-muted-foreground">{fail}</div>
          ) : (
            <>
              <div ref={boxRef} className="h-56 w-full overflow-hidden rounded-field bg-secondary" />
              <div className="flex items-center justify-between txt-caption text-muted-foreground/70">
                <span>在地图上点选或拖动标记，地址与经纬度一起写入</span>
                <Button type="button" variant="ghost" size="sm" onClick={locate}>
                  <LocateFixed className="size-4" />按地址定位
                </Button>
              </div>
            </>
          )}
        </div>
      )}

      {/* 经纬度：地图写入后在这里显示，也允许手填（没地图时这是唯一入口）。 */}
      <div className="flex items-center gap-2">
        <span className="txt-caption text-muted-foreground/70">经度</span>
        <Input
          type="number" className="h-8 w-28" disabled={disabled}
          value={value.lng ?? ""} onBlur={onBlur}
          onChange={(e) => set({ lng: num(e.target.value) })}
        />
        <span className="txt-caption text-muted-foreground/70">纬度</span>
        <Input
          type="number" className="h-8 w-28" disabled={disabled}
          value={value.lat ?? ""} onBlur={onBlur}
          onChange={(e) => set({ lat: num(e.target.value) })}
        />
      </div>
    </div>
  );
}
