<script setup lang="ts">
// pb-map: 有 VITE_GMAPS_KEY → 加载真实 Google Maps JavaScript API；无 key → 降级为样式占位。
// Capacitor WebView = H5，Maps JS API 在 WebView 中完全可用，无需 renderjs。
import { onMounted, onUnmounted, watch } from "vue";
import type { NearbyCabinet } from "@/types";

const props = defineProps<{ points: NearbyCabinet[] }>();
const emit = defineEmits<{ (e: "select", p: NearbyCabinet): void }>();

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

function buildMarkerIcon(available: boolean): google.maps.Symbol {
  return {
    path: google.maps.SymbolPath.CIRCLE,
    fillColor: available ? "#22c55e" : "#9ca3af",
    fillOpacity: 1,
    strokeColor: "#ffffff",
    strokeWeight: 2.5,
    scale: 17,
  };
}

function placeMarkers() {
  if (!gmap) return;
  markers.forEach((m) => m.setMap(null));
  markers.length = 0;

  props.points.forEach((p) => {
    if (p.lat == null || p.lng == null) return;
    const available = p.availableBorrow > 0;
    const m = new google.maps.Marker({
      position: { lat: p.lat, lng: p.lng },
      map: gmap!,
      title: p.siteName,
      label: { text: String(p.availableBorrow), color: "#fff", fontSize: "11px", fontWeight: "700" },
      icon: buildMarkerIcon(available),
      zIndex: available ? 100 : 50,
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
      icon: {
        path: google.maps.SymbolPath.CIRCLE,
        fillColor: "#2F6BFF",
        fillOpacity: 1,
        strokeColor: "#fff",
        strokeWeight: 3,
        scale: 9,
      },
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

onMounted(initGMap);
watch(() => props.points, placeMarkers, { deep: true });
onUnmounted(() => {
  markers.forEach((m) => m.setMap(null));
  userMarker?.setMap(null);
  gmap = null;
});
</script>

<template>
  <!-- 真实地图容器（有 key） -->
  <view v-if="hasKey" id="pb-map-canvas" class="pb-map pb-map--real" />

  <!-- 占位（无 key） -->
  <view v-else class="pb-map">
    <view class="pb-map__grid" />
    <view class="pb-map__me" />
    <view
      v-for="(p, i) in points.slice(0, 5)"
      :key="p.cabinetNo"
      class="pb-map__pin"
      :class="{ 'is-off': p.availableBorrow <= 0 }"
      :style="POS[i]"
      @tap="emit('select', p)"
    >
      <pb-icon name="pin" :size="46" />
      <text class="pb-map__cnt">{{ p.availableBorrow }}</text>
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
.pb-map--real { background: #e5e3df; }

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
  color: var(--pb-primary);
  display: flex;
  flex-direction: column;
  align-items: center;
}
.pb-map__pin.is-off { color: var(--pb-sub); }
.pb-map__cnt { font-size: 20rpx; font-weight: 800; margin-top: -6rpx; }
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
