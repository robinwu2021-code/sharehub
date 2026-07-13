// 全域 mock 数据集 + helper（内存）。MENA/迪拜场景、AED。对齐 ops-web mock/db 思路。
import type {
  NearbyCabinet,
  CabinetAvailability,
  RentOrder,
  UserProfile,
  Wallet,
  Coupon,
  Membership,
  Notice,
  StoreDetail,
} from "@/types";
import { CURRENCY, MOCK_DELAY_MS } from "@/shared/constants";

export const delay = <T>(v: T, ms = MOCK_DELAY_MS): Promise<T> =>
  new Promise((r) => setTimeout(() => r(v), ms));

export const kwHit = (kw: string | undefined, ...fields: (string | undefined)[]): boolean =>
  !kw || fields.some((f) => (f ?? "").toLowerCase().includes(kw.toLowerCase()));

export function paginate<T>(all: T[], page = 1, size = 10, pred?: (x: T) => boolean) {
  const filtered = pred ? all.filter(pred) : all;
  const start = (page - 1) * size;
  return { records: filtered.slice(start, start + size), total: filtered.length, page, size };
}

export const cabinets: NearbyCabinet[] = [
  { cabinetNo: "CAB-DXBM-01", siteNo: "ST-DXBM", siteName: "The Dubai Mall", address: "Downtown Dubai", distanceM: 240, lat: 25.1972, lng: 55.2796, availableBorrow: 6, availableReturn: 2, pricePerHour: 5, currency: CURRENCY, status: "ACTIVE" },
  { cabinetNo: "CAB-MOE-03", siteNo: "ST-MOE", siteName: "Mall of the Emirates", address: "Al Barsha", distanceM: 1300, lat: 25.1181, lng: 55.2003, availableBorrow: 3, availableReturn: 5, pricePerHour: 5, currency: CURRENCY, status: "ACTIVE" },
  { cabinetNo: "CAB-JBR-07", siteNo: "ST-JBR", siteName: "JBR The Walk", address: "Jumeirah Beach", distanceM: 2100, lat: 25.0785, lng: 55.1339, availableBorrow: 0, availableReturn: 8, pricePerHour: 6, currency: CURRENCY, status: "ACTIVE" },
  { cabinetNo: "CAB-DXB-T3", siteNo: "ST-DXB", siteName: "DXB Airport T3", address: "Dubai Intl Airport", distanceM: 5400, lat: 25.2528, lng: 55.3644, availableBorrow: 9, availableReturn: 1, pricePerHour: 8, currency: CURRENCY, status: "ACTIVE" },
];

export const availability: Record<string, CabinetAvailability> = Object.fromEntries(
  cabinets.map((c) => [
    c.cabinetNo,
    {
      cabinetNo: c.cabinetNo,
      siteName: c.siteName,
      borrowable: c.availableBorrow > 0,
      pricePerHour: c.pricePerHour,
      dailyCap: 30,
      buyoutPrice: 99,
      depositAmount: 50,
      freeQuota: 100,
      currency: CURRENCY,
    },
  ]),
);

export const orders: RentOrder[] = [
  {
    orderNo: "R2026071200001",
    cUserNo: "CU-0001",
    status: "IN_USE",
    cabinetNoBorrow: "CAB-DXBM-01",
    siteNameBorrow: "The Dubai Mall",
    powerBankNo: "PB-88213",
    startAt: "2026-07-12T09:40:00Z",
    durationMin: 72,
    amount: 6,
    currency: CURRENCY,
    depositAmount: 0,
    freeFrozen: 100,
    fees: [{ label: "rental", amount: 6 }],
    timeline: [
      { status: "CREATED", at: "2026-07-12T09:39:40Z" },
      { status: "DISPENSING", at: "2026-07-12T09:39:45Z" },
      { status: "IN_USE", at: "2026-07-12T09:40:00Z" },
    ],
  },
  {
    orderNo: "R2026071100042",
    cUserNo: "CU-0001",
    status: "SETTLED",
    cabinetNoBorrow: "CAB-MOE-03",
    siteNameBorrow: "Mall of the Emirates",
    cabinetNoReturn: "CAB-JBR-07",
    siteNameReturn: "JBR The Walk",
    powerBankNo: "PB-12007",
    startAt: "2026-07-11T14:10:00Z",
    endAt: "2026-07-11T16:25:00Z",
    durationMin: 135,
    amount: 15,
    currency: CURRENCY,
    depositAmount: 0,
    freeFrozen: 0,
    fees: [
      { label: "rental", amount: 18 },
      { label: "coupon", amount: -3 },
    ],
    timeline: [
      { status: "IN_USE", at: "2026-07-11T14:10:00Z" },
      { status: "RETURNED", at: "2026-07-11T16:25:00Z" },
      { status: "SETTLED", at: "2026-07-11T16:25:10Z" },
    ],
  },
];

export const profile: UserProfile = {
  cUserNo: "CU-0001",
  nickname: "Ahmed",
  phone: "+971 50 *** 1234",
  creditScore: 720,
  freeDeposit: true,
  memberLevel: "Plus",
};

export const wallet: Wallet = { balance: 24, bonus: 10, deposit: 0, frozen: 100, currency: CURRENCY };

export const coupons: Coupon[] = [
  { couponNo: "CP-01", title: "New user AED 5 off", amount: 5, threshold: 0, status: "UNUSED", expireAt: "2026-08-01" },
  { couponNo: "CP-02", title: "AED 3 off over 10", amount: 3, threshold: 10, status: "UNUSED", expireAt: "2026-07-20" },
  { couponNo: "CP-03", title: "Weekend AED 2", amount: 2, threshold: 0, status: "USED", expireAt: "2026-07-06" },
];

export const memberships: Membership[] = [
  { planNo: "MB-MONTH", name: "Monthly Pass", price: 29, benefits: ["First 2h free daily", "10% off", "Higher free-deposit"], active: true, expireAt: "2026-08-12" },
  { planNo: "MB-10", name: "10-Rides Card", price: 40, benefits: ["10 rides, 2h each"], active: false },
];

export const notices: Notice[] = [
  { noticeNo: "N-01", title: "Ramadan hours updated", body: "Selected mall stations now operate 10:00–02:00 during Ramadan.", date: "2026-07-12", read: false },
  { noticeNo: "N-02", title: "New stations at DXB T3", body: "9 new cabinets are now live at Dubai Airport Terminal 3.", date: "2026-07-08", read: true },
];

export function storeOf(siteNo: string): StoreDetail {
  const c = cabinets.find((x) => x.siteNo === siteNo) ?? cabinets[0];
  return {
    siteNo: c.siteNo,
    siteName: c.siteName,
    address: c.address,
    openHours: "10:00 – 24:00",
    lat: c.lat,
    lng: c.lng,
    distanceM: c.distanceM,
    availableBorrow: c.availableBorrow,
    availableReturn: c.availableReturn,
    pricePerHour: c.pricePerHour,
    currency: c.currency,
    favorite: false,
    cabinets: [
      { cabinetNo: c.cabinetNo, borrow: c.availableBorrow, return: c.availableReturn },
      { cabinetNo: c.cabinetNo.replace(/\d+$/, "") + "09", borrow: Math.max(0, c.availableBorrow - 2), return: c.availableReturn + 1 },
    ],
  };
}
