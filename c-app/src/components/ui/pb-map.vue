<script lang="ts">
/*
 * pb-map：附近网点地图（Google Maps JavaScript API）。
 *
 * **为什么是 renderjs**：原先这里在 `<script setup>` 里直接 `document.createElement` 注入
 * Maps 脚本，注释写着「Capacitor WebView = H5，无需 renderjs」—— 那是按 Capacitor 壳写的。
 * 改走 uni 原生打包之后，**App 的页面逻辑跑在独立的 JS 引擎里（app-service），那里没有
 * `document`**：地图永远加载不出来，也不报错，只剩一个灰块（首页默认就是地图视图）。
 * Android 模拟器装机实测撞到。
 *
 * renderjs 让 DOM 相关代码跑在**视图层 WebView** 里：H5 与 App 同一份实现。
 * 小程序不支持 renderjs，仍显示占位（小程序要地图得用原生 `<map>`，另议）。
 *
 * 分工：逻辑层（本块）只准备数据（点位、我的位置、key）并接收「点了哪个点」；
 * 画图全在下面的 renderjs 模块里。
 */
import { defineComponent, type PropType } from "vue";
import type { NearbyCabinet } from "@/types";
import { getUserLocation } from "@/ports/location";

const KEY = (import.meta.env.VITE_GMAPS_KEY as string | undefined) || "";

/** 小程序没有 renderjs，直接走占位。（写成变量而不是在 data 里条件编译两个同名字段 —— 那样 TS 不认） */
let RENDERJS = true;
// #ifdef MP
RENDERJS = false;
// #endif

// 占位用固定布局（无 key / 加载失败 / 小程序）
const POS = [
  { left: "22%", top: "30%" },
  { left: "64%", top: "24%" },
  { left: "42%", top: "60%" },
  { left: "78%", top: "56%" },
  { left: "30%", top: "46%" },
];

export default defineComponent({
  props: { points: { type: Array as PropType<NearbyCabinet[]>, default: () => [] } },
  emits: ["select"],
  data() {
    return {
      POS,
      real: RENDERJS && !!KEY,
      failed: false,
      me: null as { lat: number; lng: number } | null,
    };
  },
  computed: {
    /** 传给视图层的全部数据。只放画图要用的字段 —— 这个对象每次变化都会整份跨线程传一遍。 */
    payload(): { key: string; me: { lat: number; lng: number } | null; points: { id: string; lat: number; lng: number; n: number; name: string }[] } {
      return {
        key: KEY,
        me: this.me,
        points: this.points
          .filter((p) => p.lat != null && p.lng != null)
          .map((p) => ({ id: p.cabinetNo, lat: p.lat as number, lng: p.lng as number, n: p.availableBorrow, name: p.siteName })),
      };
    },
  },
  mounted() {
    // 成因只给排障的人看：界面上说什么都一样（用户改不了 key，也换不了浏览器）
    if (!KEY) console.warn("[pb-map] 未配置 VITE_GMAPS_KEY，地图显示为占位");
    // 定位在逻辑层取（App 走 uni.getLocation 的原生定位），拿到了再交给视图层画蓝点。
    // 「我在这儿」**只有真拿到位置才画**：先画在迪拜市中心的话，人在阿布扎比时地图会告诉他「你在迪拜」。
    getUserLocation().then((c) => {
      this.me = c;
    });
  },
  methods: {
    /** 视图层回调：点了某个网点。 */
    onPick(id: string) {
      const p = this.points.find((x) => x.cabinetNo === id);
      if (p) this.$emit("select", p);
    },
    /** 视图层回调：Maps 脚本没加载成功（网络 / key 限制）—— 退回占位，别留一个灰块。 */
    onMapFail() {
      this.failed = true;
    },
  },
});
</script>

<script module="gm" lang="renderjs">
// ⚠️ 这一块跑在视图层（WebView），不是逻辑层：这里有 document/window，
// 但拿不到逻辑层的任何变量，数据只能经 :prop 进来、经 callMethod 回去。
let gmap = null;
let markers = [];
let meMarker = null;
let loading = null;
const DUBAI = { lat: 25.2048, lng: 55.2708 };

function loadScript(key) {
  if (window.google && window.google.maps) return Promise.resolve(true);
  if (loading) return loading;
  loading = new Promise(function (resolve) {
    const cb = "__pb_gmaps_ready";
    window[cb] = function () { delete window[cb]; resolve(true); };
    const s = document.createElement("script");
    s.src = "https://maps.googleapis.com/maps/api/js?key=" + key + "&callback=" + cb + "&language=en";
    s.async = true;
    s.onerror = function () { resolve(false); };
    document.head.appendChild(s);
    // 回调永远不来（被墙 / key 被拒且没触发 onerror）时别一直挂着
    setTimeout(function () { resolve(!!(window.google && window.google.maps)); }, 15000);
  });
  return loading;
}

/**
 * 「构造了但从没画出来」的兜底。
 *
 * 现有的失败处理只管**脚本没加载成功**（onMapFail）。而还有一种：脚本加载成功、
 * `new google.maps.Map()` 也跑了（容器里能看到 Maps 的空骨架），但地图**永远没开始绘制**
 * —— 没有 .gm-style、没有图块、连一次 /maps/vt 请求都不发。
 * 2026-09-27 在本机浏览器里实测到过（该环境缺 WebGL2，而 3.66 的矢量地图要它）。
 *
 * 这种情况下用户看到的是一整块没有任何解释的空白，而下面的「用列表」出口他也不知道存在。
 * 所以给一个看门狗：到点还没画出来就当失败处理，退回占位并提示改用列表。
 *
 * 8 秒是权衡：慢网下首帧可能要几秒，太短会把能用的地图误判掉；
 * 而超过 8 秒还是空白的话，用户早就去点别处了。
 */
function watchPainted(el, owner) {
  const deadline = 8000;
  const t0 = Date.now();
  const timer = setInterval(function () {
    if (el.querySelector(".gm-style")) { clearInterval(timer); return; }
    if (Date.now() - t0 > deadline) {
      clearInterval(timer);
      // 留一句给排障的人：区分「没加载出来」和「加载了但没画」
      if (window.console) console.warn("[pb-map] Maps 已加载但未绘制，退回占位");
      owner.callMethod("onMapFail");
    }
  }, 500);
}

function icon(available) {
  return {
    path: window.google.maps.SymbolPath.CIRCLE,
    fillColor: available ? "#22c55e" : "#9ca3af",
    fillOpacity: 1,
    strokeColor: "#ffffff",
    strokeWeight: 2.5,
    scale: 17,
  };
}

export default {
  methods: {
    onPayload(p, _old, owner) {
      if (!p || !p.key) return;
      loadScript(p.key).then(function (ok) {
        if (!ok) { owner.callMethod("onMapFail"); return; }
        const g = window.google.maps;
        if (!gmap) {
          const el = document.getElementById("pb-map-canvas");
          if (!el) return;
          watchPainted(el, owner);
          gmap = new g.Map(el, {
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
        }
        markers.forEach(function (m) { m.setMap(null); });
        markers = (p.points || []).map(function (pt) {
          const m = new g.Marker({
            position: { lat: pt.lat, lng: pt.lng },
            map: gmap,
            title: pt.name,
            label: { text: String(pt.n), color: "#fff", fontSize: "11px", fontWeight: "700" },
            icon: icon(pt.n > 0),
            zIndex: pt.n > 0 ? 100 : 50,
          });
          m.addListener("click", function () { owner.callMethod("onPick", pt.id); });
          return m;
        });
        if (p.me) {
          if (!meMarker) {
            meMarker = new g.Marker({
              map: gmap,
              title: "You",
              zIndex: 9999,
              icon: { path: g.SymbolPath.CIRCLE, fillColor: "#2F6BFF", fillOpacity: 1, strokeColor: "#fff", strokeWeight: 3, scale: 9 },
            });
            gmap.setCenter(p.me);
            gmap.setZoom(14);
          }
          meMarker.setPosition(p.me);
        }
      });
    },
  },
};
</script>

<template>
  <!-- 真实地图：数据经 :prop 交给视图层的 renderjs（见上方 gm 模块） -->
  <!-- @vue-expect-error renderjs 模块 gm 由 uni 编译器注入，vue-tsc 不认识 -->
  <view v-if="real && !failed" id="pb-map-canvas" class="pb-map pb-map--real" :prop="payload" :change:prop="gm.onPayload" />

  <!-- 占位（无 key / 加载失败 / 小程序） -->
  <view v-else class="pb-map">
    <view class="pb-map__grid" />
    <view class="pb-map__me" />
    <view
      v-for="(p, i) in points.slice(0, 5)"
      :key="p.cabinetNo"
      class="pb-map__pin"
      :class="{ 'is-off': p.availableBorrow <= 0 }"
      :style="POS[i]"
      @tap="$emit('select', p)"
    >
      <pb-icon name="pin" :size="46" />
      <text class="pb-map__cnt">{{ p.availableBorrow }}</text>
    </view>
    <view class="pb-map__note">
      <pb-icon name="map" :size="26" />
      <!--
        原来这两句是写死的英文，而这是 zh/en/ar 三语应用 —— 阿语用户会看到一句英文。
        「没配 key」对用户也不是可行动的信息（他改不了环境变量），所以两种情况对外
        都说同一句「地图暂不可用，请用列表」，**成因写进控制台给排障的人**。
      -->
      <text>{{ $t("home.mapUnavailable") }}</text>
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
