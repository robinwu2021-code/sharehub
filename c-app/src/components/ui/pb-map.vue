<script setup lang="ts">
// pb-map: 有 VITE_GMAPS_KEY → 加载真实 Google Maps JavaScript API；无 key → 降级为样式占位。
// Capacitor WebView = H5，Maps JS API 在 WebView 中完全可用，无需 renderjs。
import { onMounted, onUnmounted, watch } from "vue";
import type { NearbyCabinet } from "@/types";
import { useThemeStore } from "@/stores/theme";

const props = defineProps<{ points: NearbyCabinet[]; full?: boolean; selected?: string | null }>();
const emit = defineEmits<{ (e: "select", p: NearbyCabinet): void }>();

const theme = useThemeStore();

// 从主题令牌取色，保证地图标注随 4 色系/明暗切换（严禁在此写死品牌色）
function cssVar(name: string, fallback: string): string {
  // #ifdef H5
  if (typeof document !== "undefined") {
    const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    if (v) return v;
  }
  // #endif
  return fallback;
}

const KEY = import.meta.env.VITE_GMAPS_KEY as string | undefined;
const hasKey = !!KEY;

// 占位用固定布局（无 key 时）
const POS = [
  { left: "22%", top: "30%" },
  { left: "64%", top: "24%" },
  { left: "42%", top: "60%" },
  { left: "78%", top: "56%" },
  { left: "30%", top: "46%" },
];

// --- Google Maps 实例 ---
let gmap: google.maps.Map | null = null;
const markers: google.maps.Marker[] = [];
let userMarker: google.maps.Marker | null = null;

function loadScript(): Promise<void> {
  return new Promise((resolve) => {
    if ((window as any).google?.maps) { resolve(); return; }
    const cb = "__pb_gmaps_ready";
    (window as any)[cb] = () => { delete (window as any)[cb]; resolve(); };
    const s = document.createElement("script");
    s.src = `https://maps.googleapis.com/maps/api/js?key=${KEY}&callback=${cb}&libraries=places&language=en`;
    s.async = true;
    s.onerror = () => resolve(); // 加载失败时静默降级（网络/key 无效）
    document.head.appendChild(s);
  });
}

async function getUserLocation(): Promise<{ lat: number; lng: number }> {
  const DUBAI = { lat: 25.2048, lng: 55.2708 };
  if (!navigator.geolocation) return DUBAI;
  return new Promise((resolve) => {
    navigator.geolocation.getCurrentPosition(
      (pos) => resolve({ lat: pos.coords.latitude, lng: pos.coords.longitude }),
      () => resolve(DUBAI),
      { timeout: 5000, maximumAge: 60_000 },
    );
  });
}

type PinState = "available" | "off" | "selected";

// 泪滴气泡 + 白色⚡（对齐原型）。整体 SVG data-URI，随主题令牌取色。
function pinSvg(fill: string): string {
  const zap = "M13 2 4 13.5h7L10 22l9-11.5h-7L13 2Z";
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="44" height="54" viewBox="0 0 44 54">` +
    `<path d="M22 51C14 40 4 31 4 20A18 18 0 1 1 40 20C40 31 30 40 22 51Z" fill="${fill}"/>` +
    `<g transform="translate(22 19) scale(0.82) translate(-11.5 -12)"><path d="${zap}" fill="#fff"/></g>` +
    `</svg>`;
  return "data:image/svg+xml;charset=UTF-8," + encodeURIComponent(svg);
}

function pinFill(state: PinState): string {
  if (state === "selected") return cssVar("--pb-ink", "#16171d");
  if (state === "off") return cssVar("--pb-sub", "#9ca3af");
  return cssVar("--pb-primary", "#17c3c0");
}

function buildMarkerIcon(state: PinState): google.maps.Icon {
  return {
    url: pinSvg(pinFill(state)),
    scaledSize: new google.maps.Size(44, 54),
    anchor: new google.maps.Point(22, 52),
  };
}

function pinState(p: NearbyCabinet): PinState {
  if (props.selected != null && p.siteNo === props.selected) return "selected";
  return p.availableBorrow > 0 ? "available" : "off";
}

function buildUserIcon(): google.maps.Symbol {
  return {
    path: google.maps.SymbolPath.CIRCLE,
    fillColor: cssVar("--pb-ink", "#16171d"),
    fillOpacity: 1,
    strokeColor: cssVar("--pb-surface", "#ffffff"),
    strokeWeight: 3,
    scale: 9,
  };
}

function placeMarkers() {
  if (!gmap) return;
  markers.forEach((m) => m.setMap(null));
  markers.length = 0;

  props.points.forEach((p) => {
    if (p.lat == null || p.lng == null) return;
    const state = pinState(p);
    const m = new google.maps.Marker({
      position: { lat: p.lat, lng: p.lng },
      map: gmap!,
      title: p.siteName,
      icon: buildMarkerIcon(state),
      zIndex: state === "selected" ? 200 : state === "available" ? 100 : 50,
    });
    m.addListener("click", () => emit("select", p));
    markers.push(m);
  });
}

const DUBAI = { lat: 25.2048, lng: 55.2708 };

function setUserMarker(pos: { lat: number; lng: number }) {
  if (!gmap) return;
  if (userMarker) {
    userMarker.setPosition(pos);
  } else {
    userMarker = new google.maps.Marker({
      position: pos,
      map: gmap,
      title: "You",
      icon: buildUserIcon(),
      zIndex: 9999,
    });
  }
}

async function initGMap() {
  if (!hasKey) return;
  await loadScript();
  if (!(window as any).google?.maps) return; // 加载失败降级

  const el = document.getElementById("pb-map-canvas");
  if (!el || gmap) return;

  // 先用默认中心立即构造地图，避免被地理定位阻塞
  gmap = new google.maps.Map(el, {
    center: DUBAI,
    zoom: 13,
    disableDefaultUI: true,
    zoomControl: true,
    gestureHandling: "greedy",
    styles: [
      { featureType: "poi", elementType: "labels", stylers: [{ visibility: "off" }] },
      { featureType: "transit", elementType: "labels", stylers: [{ visibility: "off" }] },
    ],
  });
  setUserMarker(DUBAI);
  placeMarkers();

  // 地理定位异步回来后 recenter
  getUserLocation().then((center) => {
    if (!gmap) return;
    setUserMarker(center);
    gmap.setCenter(center);
    gmap.setZoom(14);
  });
}

// 回到我的位置（供全屏地图的定位按钮调用）
async function recenter() {
  if (!gmap) return;
  const center = await getUserLocation();
  setUserMarker(center);
  gmap.setCenter(center);
  gmap.setZoom(14);
}
defineExpose({ recenter });

onMounted(initGMap);
watch(() => props.points, placeMarkers, { deep: true });
watch(() => props.selected, placeMarkers);
// 换肤/明暗切换时，JS 绘制的标注不会自动重绘 → 手动按新令牌重建
watch(
  () => [theme.skin, theme.mode],
  () => {
    if (!gmap) return;
    placeMarkers();
    userMarker?.setIcon(buildUserIcon());
  },
);
onUnmounted(() => {
  markers.forEach((m) => m.setMap(null));
  userMarker?.setMap(null);
  gmap = null;
});
</script>

<template>
  <!-- 真实地图容器（有 key） -->
  <view v-if="hasKey" id="pb-map-canvas" class="pb-map pb-map--real" :class="{ 'pb-map--full': full }" />

  <!-- 占位（无 key） -->
  <view v-else class="pb-map" :class="{ 'pb-map--full': full }">
    <view class="pb-map__grid" />
    <view class="pb-map__me" />
    <view
      v-for="(p, i) in points.slice(0, 5)"
      :key="p.cabinetNo"
      class="pb-map__pin"
      :class="{ 'is-off': p.availableBorrow <= 0, 'is-sel': selected === p.siteNo }"
      :style="POS[i]"
      @tap="emit('select', p)"
    >
      <view class="pb-map__balloon"><pb-icon name="zap" :size="30" /></view>
    </view>
    <view class="pb-map__note">
      <pb-icon name="map" :size="26" />
      <text>Set VITE_GMAPS_KEY to enable map</text>
    </view>
  </view>
</template>

<style scoped>
/* 共用容器：默认填满父高度（首页 flex 布局），无父高度时回退最小高度 */
.pb-map {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 420rpx;
  border-radius: 28rpx;
  overflow: hidden;
  background: var(--pb-faint);
  box-shadow: var(--pb-shadow);
}
/* 真实地图：Google Maps 自行填满 */
.pb-map--real { background: var(--pb-faint); }
/* 全屏模式：去圆角/阴影，边到边铺满 */
.pb-map--full { border-radius: 0; box-shadow: none; min-height: 0; }

/* 占位样式 */
.pb-map__grid {
  position: absolute;
  inset: 0;
  background-image: linear-gradient(rgba(0, 0, 0, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(0, 0, 0, 0.05) 1px, transparent 1px);
  background-size: 56rpx 56rpx;
  opacity: 0.5;
}
.pb-map__me {
  position: absolute;
  left: 50%; top: 50%;
  width: 26rpx; height: 26rpx;
  margin: -13rpx;
  border-radius: 9999px;
  background: var(--pb-primary);
  box-shadow: 0 0 0 10rpx var(--pb-primary-tint);
}
.pb-map__pin {
  position: absolute;
  transform: translate(-50%, -100%);
}
/* 泪滴气泡：圆角方块留一个尖角 + 旋转 45° 使尖角朝下；内部⚡反向旋转保持正立 */
.pb-map__balloon {
  width: 56rpx;
  height: 56rpx;
  border-radius: 50% 50% 50% 0;
  transform: rotate(-45deg);
  background: var(--pb-primary);
  color: var(--pb-on-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 6rpx 16rpx var(--pb-primary-tint);
}
.pb-map__balloon .pb-icon { transform: rotate(45deg); }
.pb-map__pin.is-off .pb-map__balloon { background: var(--pb-sub); box-shadow: none; }
.pb-map__pin.is-sel .pb-map__balloon { background: var(--pb-ink); }
.pb-map__note {
  position: absolute;
  right: 16rpx; bottom: 16rpx;
  display: flex;
  align-items: center;
  gap: 8rpx;
  background: var(--pb-surface);
  border-radius: 9999px;
  padding: 8rpx 16rpx;
  font-size: 20rpx;
  color: var(--pb-sub);
  box-shadow: var(--pb-shadow-sm);
}
</style>
