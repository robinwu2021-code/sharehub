"use client";

// 地址选点：地址**搜索框** + 地图 + 经纬度，三个值绑成一个控件。
//
// 为什么不是三个独立字段：地址与经纬度**必须一致**，分开手填时两者对不上没人拦得住 ——
// C 端「找附近」按经纬度排序、运营端撒点按经纬度落点，地址只是给人看的。
//
// 三条写入路径，任何一条都同时写这三个值：
//   ① 输入关键词 → 调 Places 搜索 → 选一条 → 地址、经纬度、地图落点一起写；
//   ② 在地图上点 / 拖标记 → 逆地理编码回填地址；
//   ③ 手填（没有地图密钥时这是唯一入口）。
//
// 降级：没配密钥或脚本加载不出来时**不白屏也不报错** —— 地址照样手填，
// 经纬度露出两个小输入框。建站点这件事本身不被地图挡住。
//
// 供应商：Google（见 lib/gmaps.ts 顶部那组瓦片对照 —— 高德在阿联酋没有数据，
// 而且它不报错，只是底图一片空白）。
import * as React from "react";
import { MapPin, LocateFixed, Search, Loader2 } from "lucide-react";
import { Input } from "./input";
import { Button } from "./button";
import { cn } from "@/lib/utils";
import {
  loadGoogleMaps, gmapsReasonText, searchPlaces, placeDetail, reverseGeocode,
  newSessionToken, useDebounced, FALLBACK_CENTER, type PlaceHit,
} from "@/lib/gmaps";

export type AddressValue = { address: string; lat: number | ""; lng: number | "" };

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
  const [query, setQuery] = React.useState("");
  const [hits, setHits] = React.useState<PlaceHit[]>([]);
  const [searching, setSearching] = React.useState(false);
  const [listOpen, setListOpen] = React.useState(false);

  const boxRef = React.useRef<HTMLDivElement | null>(null);
  const mapRef = React.useRef<google.maps.Map | null>(null);
  const markerRef = React.useRef<google.maps.Marker | null>(null);
  const wrapRef = React.useRef<HTMLDivElement | null>(null);
  /** 一次搜索会话：多次补全 + 一次详情按一次计费。选中或放弃后换新的。 */
  const session = React.useRef(newSessionToken());

  // onChange 会随每次输入变新，但地图事件只在初始化时绑一次 —— 用 ref 取最新的，
  // 否则地图里点一下写回的是「打开地图那一刻」的旧值（闭包陷阱，
  // 表现为经纬度写了、地址没写）。
  const latest = React.useRef({ value, onChange });
  latest.current = { value, onChange };

  const set = (patch: Partial<AddressValue>) => {
    const cur = latest.current.value;
    latest.current.onChange({ ...cur, ...patch });
  };

  // ───────────────── 搜索 ─────────────────

  const debounced = useDebounced(query, 350);
  React.useEffect(() => {
    const q = debounced.trim();
    if (q.length < 2) { setHits([]); return; }
    let dead = false;
    setSearching(true);
    const bias = latest.current.value.lat !== "" && latest.current.value.lng !== ""
      ? { lat: Number(latest.current.value.lat), lng: Number(latest.current.value.lng) }
      : FALLBACK_CENTER;
    searchPlaces(q, session.current, bias).then((r) => {
      if (dead) return;
      setHits(r);
      setSearching(false);
      setListOpen(true);
    });
    return () => { dead = true; };
  }, [debounced]);

  /** 选中一条候选：地址 / 坐标 / 地图落点一起写。 */
  async function pick(hit: PlaceHit) {
    setListOpen(false);
    const d = await placeDetail(hit.placeId, session.current);
    session.current = newSessionToken();   // 一次会话结束
    if (!d) { setFail("这条地点取不到坐标，可在地图上直接点选"); return; }
    setFail(null);
    setQuery("");
    set({ address: d.address, lat: d.lat, lng: d.lng });
    focusMap(d.lat, d.lng);
    setOpen(true);      // 选完直接把地图展开，让人看见落在哪儿
  }

  /** 把地图与标记挪到某个坐标。地图还没初始化时只写值，等展开时会以它为中心。 */
  function focusMap(lat: number, lng: number) {
    const m = mapRef.current;
    if (!m) return;
    m.setCenter({ lat, lng });
    m.setZoom(16);
    markerRef.current?.setPosition({ lat, lng });
  }

  /** 坐标 → 地址。查不到就只写坐标，**不清空**用户已填的地址。 */
  async function writeBack(lat: number, lng: number) {
    set({ lat, lng });
    const addr = await reverseGeocode(lat, lng);
    if (addr) set({ address: addr });
  }

  /** 「按地址定位」：拿输入框里已有的地址去搜第一条。 */
  async function locate() {
    const addr = latest.current.value.address?.trim();
    if (!addr) return;
    const r = await searchPlaces(addr, session.current);
    if (!r.length) { setFail("按这个地址没找到坐标，可在地图上直接点选"); return; }
    await pick(r[0]);
  }

  // ───────────────── 地图 ─────────────────

  React.useEffect(() => {
    if (!open || mapRef.current) return;
    let dead = false;
    loadGoogleMaps().then((r) => {
      if (dead || !boxRef.current) return;
      if (!r.ok) { setFail(gmapsReasonText(r.reason)); return; }
      const v = latest.current.value;
      const has = v.lat !== "" && v.lng !== "";
      const center = has ? { lat: Number(v.lat), lng: Number(v.lng) } : FALLBACK_CENTER;
      const map = new google.maps.Map(boxRef.current, {
        center, zoom: has ? 16 : 11,
        mapTypeControl: false, streetViewControl: false, fullscreenControl: false,
      });
      const marker = new google.maps.Marker({ position: center, draggable: true, map });
      map.addListener("click", (e: google.maps.MapMouseEvent) => {
        if (!e.latLng) return;
        marker.setPosition(e.latLng);
        void writeBack(e.latLng.lat(), e.latLng.lng());
      });
      marker.addListener("dragend", () => {
        const p = marker.getPosition();
        if (p) void writeBack(p.lat(), p.lng());
      });
      mapRef.current = map;
      markerRef.current = marker;
    });
    return () => { dead = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  // 收起地图时丢掉实例，下次展开重建 —— 否则容器被卸载后地图挂在一个已消失的节点上
  React.useEffect(() => {
    if (!open) { mapRef.current = null; markerRef.current = null; }
  }, [open]);

  // 点到别处收起候选列表（列表是浮层，不收的话会盖住下面的字段）
  React.useEffect(() => {
    if (!listOpen) return;
    const onDown = (e: MouseEvent) => {
      if (!wrapRef.current?.contains(e.target as Node)) setListOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, [listOpen]);

  const num = (s: string) => (s === "" ? "" : Number(s));

  return (
    <div className="space-y-2">
      {/* 搜索框：输关键词找地点。选中后地址、坐标、地图一起写 */}
      <div ref={wrapRef} className="relative">
        <div className="flex gap-2">
          <div className="relative flex-1">
            <Search className="pointer-events-none absolute start-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground/60" />
            <Input
              className="ps-8"
              value={query}
              disabled={disabled}
              placeholder="搜索地点，如 Mall of the Emirates"
              onChange={(e) => { setQuery(e.target.value); setFail(null); }}
              onFocus={() => hits.length && setListOpen(true)}
            />
            {searching && (
              <Loader2 className="absolute end-2.5 top-1/2 size-4 -translate-y-1/2 animate-spin text-muted-foreground/60" />
            )}
          </div>
          <Button type="button" variant="outline" size="sm" disabled={disabled} onClick={() => setOpen((o) => !o)}>
            <MapPin className="size-4" />
            {open ? "收起地图" : "地图选点"}
          </Button>
        </div>

        {listOpen && hits.length > 0 && (
          <ul
            role="listbox"
            className="absolute inset-x-0 top-full z-[var(--z-popover)] mt-1 max-h-64 overflow-auto rounded-field border border-border bg-card py-1 shadow-pop"
          >
            {hits.map((h) => (
              <li key={h.placeId}>
                <button
                  type="button"
                  role="option"
                  aria-selected={false}
                  className="w-full px-3 py-2 text-start hover:bg-muted"
                  onClick={() => void pick(h)}
                >
                  <div className="txt-body text-foreground">{h.main}</div>
                  {h.secondary && <div className="txt-caption text-muted-foreground">{h.secondary}</div>}
                </button>
              </li>
            ))}
          </ul>
        )}
        {listOpen && !searching && debounced.trim().length >= 2 && hits.length === 0 && (
          <div className="absolute inset-x-0 top-full z-[var(--z-popover)] mt-1 rounded-field border border-border bg-card px-3 py-2 txt-caption text-muted-foreground">
            没搜到这个地点。换个关键词，或直接在地图上点选。
          </div>
        )}
      </div>

      {/* 选定的地址：搜索写入，也允许手改（地址是给人看的，运营常要补楼层门牌） */}
      <Input
        className={cn(invalid && "ring-2 ring-destructive")}
        value={value.address ?? ""}
        disabled={disabled}
        placeholder={placeholder}
        onBlur={onBlur}
        onChange={(e) => set({ address: e.target.value })}
      />

      {open && (
        <div className="space-y-2">
          {fail ? (
            <div className="rounded-field bg-secondary px-3 py-2 txt-caption text-muted-foreground">{fail}</div>
          ) : (
            <>
              <div ref={boxRef} className="h-56 w-full overflow-hidden rounded-field bg-secondary" />
              <div className="flex items-center justify-between txt-caption text-muted-foreground/70">
                <span>在地图上点选或拖动标记，地址与经纬度一起写入</span>
                <Button type="button" variant="ghost" size="sm" onClick={() => void locate()}>
                  <LocateFixed className="size-4" />按地址定位
                </Button>
              </div>
            </>
          )}
        </div>
      )}

      {/* 经纬度：搜索/地图写入后在这里显示，也允许手填（没地图时这是唯一入口）。 */}
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
