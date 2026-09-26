"use client";

// 地址录入：**一个输入框**搞定地址 + 经纬度。
//
// ── 为什么只有一个输入框 ──────────────────────────────────────────
// 第一版摆了四个：搜索框、地址框、经度、纬度。运营看到的是「我该填哪个」——
// 而这四个其实是**同一件事的四种表示**，而且必须彼此一致：
// C 端「找附近」按坐标排、运营端撒点按坐标落点，地址只是给人看的。
// 摆四个框等于把「保持一致」这件事交给填表的人，那是拦不住的。
//
// 现在：输入框既是**搜索**也是**最终地址**。
//   · 边打边联想（Places 自动补全，天然容错 —— 打错几个字母照样命中）；
//   · 选中一条 → 地址、经纬度、地图落点**一起写**；
//   · 地图上点 / 拖标记微调 → 逆地理编码回填地址；
//   · 坐标是**只读展示**，不是输入框 —— 它由上面两条路产生，手填只会制造不一致。
//
// ── 联想列表不压地图 ─────────────────────────────────────────────
// 列表**走正常文档流**，不是绝对定位浮层。浮层版本实测正好盖在地图上，
// 而这两样恰恰要一起看（「我选的这条是不是落在我想的位置」）。
// 走文档流的代价是列表出现时下面的内容会被推下去 —— 在抽屉里这没问题，
// 换来的是两者永远不重叠，也不必跟 z-index 打架。
//
// ── 降级 ────────────────────────────────────────────────────────
// 没配密钥或脚本加载不出来时**不白屏也不报错**：地址照常手填，
// 并且**这时才**露出两个坐标小输入框（那是唯一入口）。建站点不被地图挡住。
//
// 供应商：Google（见 lib/gmaps.ts 顶部那组瓦片对照 —— 高德在阿联酋没有数据，
// 而且它不报错，只是底图一片空白）。
import * as React from "react";
import { MapPin, Search, Loader2, Check } from "lucide-react";
import { Input } from "./input";
import { Button } from "./button";
import { cn } from "@/lib/utils";
import {
  loadGoogleMaps, gmapsReasonText, gmapsConfigured, searchPlaces, reverseGeocode,
  useDebounced, FALLBACK_CENTER, PLACE_TYPES, type PlaceHit,
} from "@/lib/gmaps";

export type AddressValue = { address: string; lat: number | ""; lng: number | "" };

/**
 * 读一个 CSS 变量的计算值。
 *
 * Google Maps 的 Marker 图标只收颜色**字符串**，塞不进 `var(--x)`，
 * 所以把 token 读出来再给它 —— 换肤时图上这个点会跟着变。
 *
 * **取不到就回空串**，调用方据此不传自定义图标、用 Google 默认针。
 * 不在这里写一个兜底色：那既违反「组件层不写死颜色」，
 * 也是在为一个几乎不可能发生的情况（globals.css 一定定义了这些 token）发明配色。
 */
function cssVar(name: string): string {
  if (typeof window === "undefined") return "";
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

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
  const [mapOpen, setMapOpen] = React.useState(false);
  const [fail, setFail] = React.useState<string | null>(null);
  const [hits, setHits] = React.useState<PlaceHit[]>([]);
  const [searching, setSearching] = React.useState(false);
  const [listOpen, setListOpen] = React.useState(false);
  /** 按地点类型筛（购物中心 / 机场 / 酒店…）。空 = 不限。 */
  const [type, setType] = React.useState("");
  /** 只搜当前地图范围 —— 「这一片有哪些商场」比「全城叫 mall 的」有用得多。 */
  const [inView, setInView] = React.useState(false);

  const boxRef = React.useRef<HTMLDivElement | null>(null);
  const mapRef = React.useRef<google.maps.Map | null>(null);
  const markerRef = React.useRef<google.maps.Marker | null>(null);
  /** 候选在地图上的标记（与选中的那个主标记分开管）。 */
  const hitMarkersRef = React.useRef<google.maps.Marker[]>([]);
  /**
   * 刚选中/刚回填过，这一轮不要再搜。
   * 没有它的话：选中 → 地址被写成完整地址 → 防抖触发 → 又弹一次列表，
   * 而人已经在看地图了，列表又把它推下去。
   */
  const skipSearch = React.useRef(false);

  // onChange 会随每次输入变新，但地图事件只在初始化时绑一次 —— 用 ref 取最新的，
  // 否则地图里点一下写回的是「打开地图那一刻」的旧值（闭包陷阱，
  // 表现为经纬度写了、地址没写）。
  const latest = React.useRef({ value, onChange });
  latest.current = { value, onChange };

  const set = (patch: Partial<AddressValue>) => {
    const cur = latest.current.value;
    latest.current.onChange({ ...cur, ...patch });
  };

  const hasCoords = value.lat !== "" && value.lng !== "";

  // ───────────────── 联想 ─────────────────

  const debounced = useDebounced(value.address ?? "", 350);
  React.useEffect(() => {
    if (skipSearch.current) { skipSearch.current = false; return; }
    const q = debounced.trim();
    // 选了类型但没打字 → 按这个类型浏览（用它自带的英文关键词当查询词）
    const typeKeyword = PLACE_TYPES.find((t) => t.value === type)?.keyword ?? "";
    const text = q.length >= 2 ? q : (type ? typeKeyword : "");
    if (!gmapsConfigured || !text) { setHits([]); setListOpen(false); clearHitMarkers(); return; }
    let dead = false;
    setSearching(true);
    const bias = hasCoords
      ? { lat: Number(latest.current.value.lat), lng: Number(latest.current.value.lng) }
      : FALLBACK_CENTER;
    const b = inView ? viewBounds() : null;
    searchPlaces(text, { type: type || undefined, bias, bounds: b }).then((r) => {
      if (dead) return;
      setHits(r);
      setSearching(false);
      setListOpen(r.length > 0);
      drawHitMarkers(r);
    });
    return () => { dead = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debounced, type, inView]);

  /** 当前地图视野的矩形范围；地图没开就没有范围可用。 */
  function viewBounds() {
    const b = mapRef.current?.getBounds();
    if (!b) return null;
    const ne = b.getNorthEast(), sw = b.getSouthWest();
    return { south: sw.lat(), west: sw.lng(), north: ne.lat(), east: ne.lng() };
  }

  function clearHitMarkers() {
    hitMarkersRef.current.forEach((m) => m.setMap(null));
    hitMarkersRef.current = [];
  }

  /**
   * 把候选撒到地图上，点标记等于点列表里那一条。
   * 候选用小圆点、选中的用默认大头针 —— 两者必须一眼分得开，
   * 否则人不知道「我到底选中了哪个」。
   */
  function drawHitMarkers(list: PlaceHit[]) {
    const map = mapRef.current;
    clearHitMarkers();
    if (!map || list.length === 0) return;
    // 把视野框住全部候选 —— 否则中心还停在默认视野，一半是海，
    // 「在图上看看再选」这件事就没发生
    const bounds = new google.maps.LatLngBounds();
    list.forEach((h) => bounds.extend({ lat: h.lat, lng: h.lng }));
    map.fitBounds(bounds, 32);
    hitMarkersRef.current = list.map((h) => {
      const fill = cssVar("--primary");
      const stroke = cssVar("--surface");
      const m = new google.maps.Marker({
        position: { lat: h.lat, lng: h.lng }, map, title: h.name,
        // 候选是小圆点、选中的是默认大头针 —— 两者必须一眼分得开。
        // token 读不到时不传 icon，退成默认针（形状仍与选中的一样，但这只是兜底路径）。
        ...(fill && stroke ? {
          icon: {
            path: google.maps.SymbolPath.CIRCLE, scale: 6,
            fillColor: fill, fillOpacity: 0.9, strokeColor: stroke, strokeWeight: 2,
          },
        } : {}),
      });
      m.addListener("click", () => pick(h));
      return m;
    });
  }

  /**
   * 选中一条：地址 / 坐标 / 地图落点一起写。
   * **不再需要二次请求** —— searchText 已经把坐标带回来了。
   */
  function pick(hit: PlaceHit) {
    setListOpen(false);
    clearHitMarkers();
    setFail(null);
    skipSearch.current = true;
    set({ address: hit.address || hit.name, lat: hit.lat, lng: hit.lng });
    setMapOpen(true);     // 让人当场看见落在哪儿
    focusMap(hit.lat, hit.lng);
  }

  function focusMap(lat: number, lng: number) {
    const m = mapRef.current;
    if (!m) return;
    m.setCenter({ lat, lng });
    m.setZoom(16);
    markerRef.current?.setPosition({ lat, lng });
    markerRef.current?.setMap(m);
  }

  /** 坐标 → 地址。查不到就只写坐标，**不清空**用户已填的地址。 */
  async function writeBack(lat: number, lng: number) {
    set({ lat, lng });
    const addr = await reverseGeocode(lat, lng);
    if (addr) { skipSearch.current = true; set({ address: addr }); }
  }

  // ───────────────── 地图 ─────────────────

  React.useEffect(() => {
    if (!mapOpen || mapRef.current) return;
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
      /*
       * 主标记（选中的那个）**没坐标时不显示**。
       * 一直显示的话，它会杵在默认视野中心 —— 看着像「已经选好了」，
       * 而那个位置是迪拜市中心，不是用户选的任何地方。
       */
      const marker = new google.maps.Marker({ position: center, draggable: true, map: has ? map : null });
      map.addListener("click", (e: google.maps.MapMouseEvent) => {
        if (!e.latLng) return;
        marker.setPosition(e.latLng);
        marker.setMap(map);   // 第一次点图时它才出现
        void writeBack(e.latLng.lat(), e.latLng.lng());
      });
      marker.addListener("dragend", () => {
        const p = marker.getPosition();
        if (p) void writeBack(p.lat(), p.lng());
      });
      mapRef.current = map;
      markerRef.current = marker;
      // 先搜后开图时，候选还没撒上去 —— 这里补一次
      if (hits.length) drawHitMarkers(hits);
    });
    return () => { dead = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mapOpen]);

  // 收起地图时丢掉实例，下次展开重建 —— 否则容器卸载后地图挂在一个已消失的节点上
  React.useEffect(() => {
    if (!mapOpen) {
      // 候选标记也要丢：它们挂在一个已经卸载的地图上，留着下次会当作"还在图上"
      hitMarkersRef.current = [];
      mapRef.current = null;
      markerRef.current = null;
    }
  }, [mapOpen]);

  const num = (s: string) => (s === "" ? "" : Number(s));
  /** 密钥没配 / 脚本没起来 —— 这时坐标只能手填，是唯一入口。 */
  const degraded = !gmapsConfigured || Boolean(fail);

  return (
    <div className="space-y-2">
      {/* 唯一的输入框：既是搜索也是最终地址 */}
      <div className="flex gap-2">
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute start-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground/60" />
          <Input
            className={cn("ps-8", invalid && "ring-2 ring-destructive")}
            value={value.address ?? ""}
            disabled={disabled}
            placeholder={placeholder ?? "输入地点名或地址，边打边找"}
            onBlur={onBlur}
            onChange={(e) => { setFail(null); set({ address: e.target.value }); }}
            onFocus={() => hits.length > 0 && setListOpen(true)}
          />
          {searching && (
            <Loader2 className="absolute end-2.5 top-1/2 size-4 -translate-y-1/2 animate-spin text-muted-foreground/60" />
          )}
        </div>
        <Button
          type="button" variant="outline" size="sm"
          disabled={disabled || !gmapsConfigured}
          onClick={() => setMapOpen((o) => !o)}
        >
          <MapPin className="size-4" />
          {mapOpen ? "收起地图" : "地图"}
        </Button>
      </div>

      {/* 换个方式搜：按类型（选了类型不打字也能按类型浏览）、只搜当前地图范围 */}
      {gmapsConfigured && !disabled && (
        <div className="flex flex-wrap items-center gap-2 txt-caption text-muted-foreground">
          <select
            className="h-7 rounded-control border border-border bg-card px-2 txt-caption"
            value={type}
            onChange={(e) => setType(e.target.value)}
            aria-label="按地点类型筛选"
          >
            <option value="">不限类型</option>
            {PLACE_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
          </select>
          {mapOpen && (
            <label className="flex items-center gap-1.5">
              <input
                type="checkbox" className="size-3.5 accent-primary"
                checked={inView} onChange={(e) => setInView(e.target.checked)}
              />
              只搜当前地图范围
            </label>
          )}
          {type && !(value.address ?? "").trim() && <span>已按「{PLACE_TYPES.find((t) => t.value === type)?.label}」列出附近的</span>}
        </div>
      )}

      {/*
        候选列表走**文档流**：它一出现就把地图推下去，而不是盖在地图上。
        这两样恰恰要一起看 ——「我选的这条是不是落在我想的位置」。
      */}
      {listOpen && hits.length > 0 && (
        <ul role="listbox" className="max-h-56 overflow-auto rounded-field border border-border bg-card py-1">
          {hits.map((h) => (
            <li key={h.placeId}>
              <button
                type="button" role="option" aria-selected={false}
                className="w-full px-3 py-2 text-start hover:bg-muted"
                onClick={() => pick(h)}
              >
                <div className="flex items-baseline gap-2">
                  <span className="txt-body text-foreground">{h.name}</span>
                  {h.typeLabel && <span className="txt-caption text-muted-foreground/70">{h.typeLabel}</span>}
                </div>
                {h.address && <div className="txt-caption text-muted-foreground">{h.address}</div>}
              </button>
            </li>
          ))}
        </ul>
      )}

      {fail && (
        <div className="rounded-field bg-secondary px-3 py-2 txt-caption text-muted-foreground">{fail}</div>
      )}

      {mapOpen && !fail && (
        <>
          <div ref={boxRef} className="h-56 w-full overflow-hidden rounded-field bg-secondary" />
          <p className="txt-caption text-muted-foreground/70">在地图上点选或拖动标记可微调，地址会跟着更新</p>
        </>
      )}

      {/*
        坐标：正常情况下**只读展示**。它由「选中联想」或「地图点选」产生 ——
        手填只会制造地址与坐标不一致，而那种不一致没人拦得住、也看不出来。
        只有在地图用不了时才退成两个输入框（那时它是唯一入口）。
      */}
      {degraded ? (
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
      ) : hasCoords ? (
        <p className="flex items-center gap-1.5 txt-caption text-muted-foreground">
          <Check className="size-3.5 text-success" />
          已定位 <span className="tabular-nums">{Number(value.lat).toFixed(6)}, {Number(value.lng).toFixed(6)}</span>
        </p>
      ) : (
        // 只说**状态**，不重复字段 help 里的说明 —— 两句话说同一件事，
        // 人会以为它们讲的是两回事，然后两句都不看
        <p className="txt-caption text-muted-foreground/70">尚未定位</p>
      )}
    </div>
  );
}
