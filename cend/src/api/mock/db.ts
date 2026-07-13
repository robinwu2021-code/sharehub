// C 端内存 mock 数据集（仅 VITE_USE_MOCK=1 使用）。数据风格对齐 MENA/AED。
import type { NearbyCabinet, RentOrder, Profile, Wallet } from "../types";

const SITES = [
  "Dubai Mall L1", "Mall of Emirates", "DXB T3", "Marina Walk",
  "City Centre Deira", "Yas Mall", "Ibn Battuta", "JBR Beach",
];
const iso = (offsetMs: number) => new Date(Date.UTC(2026, 6, 12, 12, 0, 0) - offsetMs).toISOString();

export const profile: Profile = {
  cUserNo: "U3001", nickname: "Ahmed", phone: "+971500000001",
  creditScore: 720, memberLevel: "NONE", blacklisted: false,
};

export const wallet: Wallet = { balance: 25, bonus: 5, deposit: 0, frozen: 50, currency: "AED" };

export const nearby: NearbyCabinet[] = SITES.map((s, i) => ({
  cabinetNo: `CAB${1000 + i}`, siteName: s, address: `${s}, Dubai, UAE`,
  distanceKm: Math.round((0.2 + i * 0.35) * 10) / 10,
  available: (i * 5) % 9, returnableSlots: 2 + (i % 5),
  priceBrief: "免费 5 分钟 · AED 3/30 分钟 · 日封顶 30",
  lat: 25.19 + i * 0.01, lng: 55.27 + i * 0.01,
}));

const OSTATUS: RentOrder["status"][] = ["IN_USE", "SETTLED", "CLOSED", "RETURNED", "EXCEPTION"];
export const orders: RentOrder[] = Array.from({ length: 10 }, (_, i) => {
  const st = OSTATUS[i % OSTATUS.length];
  const dur = st === "IN_USE" ? null : 20 + (i * 17) % 200;
  return {
    orderNo: `ORD${600000 + i}`, cUserNo: "U3001", cabinetNo: `CAB${1000 + (i % 8)}`,
    returnCabinetNo: st === "SETTLED" || st === "CLOSED" ? `CAB${1000 + ((i + 3) % 8)}` : null,
    powerbankNo: `PB${2000 + i}`, siteName: SITES[i % SITES.length], status: st,
    rentStartAt: iso(i * 3600_000), rentEndAt: dur ? iso(i * 3600_000 - dur * 60000) : null,
    durationMin: dur, feeAmount: dur ? Math.min(30, Math.ceil(dur / 30) * 3) : 0,
    depositAmount: 50, currency: "AED",
  };
});

export function paginate<T>(all: T[], page = 1, size = 10, filter?: (t: T) => boolean): { list: T[]; total: number; page: number; size: number } {
  const rows = filter ? all.filter(filter) : all;
  const start = (page - 1) * size;
  return { list: rows.slice(start, start + size), total: rows.length, page, size };
}
