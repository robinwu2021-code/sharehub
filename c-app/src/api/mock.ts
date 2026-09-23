// Mock 实现（McpApi）。全部走 mock/db 内存数据 + 模拟延迟。后端未就绪即可跑通全部 UI。
import * as db from "@/mock/db";
import type { McpApi, LoginParams, RentParams, PayParams, OrderQ, NearbyQ } from "./contract";
import type { RentOrder } from "@/types";

export const mockApi: McpApi = {
  login: (p: LoginParams) =>
    db.delay({ token: `mock-c-${p.grantType}`, cUserNo: "CU-0001", nickname: db.profile.nickname, isNew: false }),
  sendOtp: () => db.delay({ cooldown: 60 }, 300),
  register: (p) => db.delay({ token: "mock-c-register", cUserNo: "CU-0001", nickname: p.nickname, isNew: true }, 500),
  resetPassword: () => db.delay({ ok: true as const }, 400),
  // mock 没有服务端会话可吊销，但必须存在：缺这个方法，mock 模式点登出会直接 TypeError
  logout: () => db.delay(undefined as void, 100),
  getProfile: () => db.delay(db.profile),
  updateProfile: (p) => {
    Object.assign(db.profile, p);
    return db.delay(db.profile, 400);
  },
  listNotices: () => db.delay(db.notices),
  storeDetail: (siteNo: string) => db.delay(db.storeOf(siteNo)),

  listFavorites: () => db.delay(db.cabinets.filter((c) => db.favorites.has(c.siteNo))),
  toggleFavorite: (siteNo: string) => {
    if (db.favorites.has(siteNo)) db.favorites.delete(siteNo);
    else db.favorites.add(siteNo);
    return db.delay({ favorite: db.favorites.has(siteNo) }, 300);
  },
  ongoingOrder: () => db.delay(db.orders.find((o) => o.status === "IN_USE" || o.status === "DISPENSING") ?? null),
  buyout: (orderNo: string) => {
    const o = db.orders.find((x) => x.orderNo === orderNo);
    if (o) {
      const now = new Date().toISOString();
      o.status = "SETTLED";
      o.amount = 99;
      o.fees = [{ label: "buyout", amount: 99 }];
      o.endAt = now;
      o.timeline.push({ status: "SETTLED", at: now });
    }
    return db.delay(o ?? db.orders[0], 450);
  },

  nearbyCabinets: (q: NearbyQ = {}) =>
    db.delay(
      db.cabinets.filter(
        (c) => db.kwHit(q.keyword, c.siteName, c.address) && (!q.returnable || c.availableReturn > 0),
      ),
    ),
  cabinetAvailability: (cabinetNo: string) =>
    db.delay(db.availability[cabinetNo] ?? { ...Object.values(db.availability)[0], cabinetNo, borrowable: false }),

  rentOrder: (p: RentParams) => {
    const cab = db.cabinets.find((c) => c.cabinetNo === p.cabinetNo) ?? db.cabinets[0];
    const now = new Date().toISOString();
    const order: RentOrder = {
      orderNo: `R${Math.floor(performance.now() * 1000)}`,
      cUserNo: "CU-0001",
      status: "IN_USE",
      cabinetNoBorrow: cab.cabinetNo,
      siteNameBorrow: cab.siteName,
      powerBankNo: "PB-90001",
      startAt: now,
      durationMin: 0,
      amount: 0,
      currency: cab.currency,
      depositAmount: p.useFreeDeposit ? 0 : db.availability[cab.cabinetNo]?.depositAmount ?? 50,
      freeFrozen: p.useFreeDeposit ? db.availability[cab.cabinetNo]?.freeQuota ?? 100 : 0,
      fees: [{ label: "rental", amount: 0 }],
      timeline: [
        { status: "CREATED", at: now },
        { status: "DISPENSING", at: now },
        { status: "IN_USE", at: now },
      ],
    };
    db.orders.unshift(order);
    return db.delay(order, 480);
  },
  getOrder: (orderNo: string) => db.delay(db.orders.find((o) => o.orderNo === orderNo) ?? db.orders[0]),
  listOrders: (q: OrderQ = {}) =>
    db.delay(db.paginate(db.orders, q.page, q.size, (o) => db.kwHit(q.keyword, o.orderNo, o.siteNameBorrow) && (!q.status || o.status === q.status))),

  depositFree: (_cabinetNo: string) => db.delay({ authNo: `AUTH${Math.floor(performance.now())}`, frozen: 100 }, 420),
  pay: (p: PayParams) => db.delay({ payNo: `PAY${Math.floor(performance.now())}`, status: "SUCCESS" as const, cashierParams: { scene: p.scene } }, 480),

  report: (_p) => db.delay({ reportNo: `RP${Math.floor(performance.now())}`, woNo: `WO${Math.floor(performance.now())}`, status: "OPEN" }, 400),

  getWallet: () => db.delay(db.wallet),
  walletTxns: (q = {}) => db.delay(db.paginate(db.walletTxns, q.page, q.size)),
  listCoupons: () => db.delay(db.coupons),
  listMemberships: () => db.delay(db.memberships),
};
