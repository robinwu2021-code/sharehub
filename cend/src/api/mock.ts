// Mock 实现（CApi 契约）。全部走内存 db + 模拟延迟。
import * as db from "./mock/db";
import type { CApi } from "./contract";
import type { OrderQ, PageQ, RentOrder } from "./types";

const wait = <T>(v: T, ms = 200): Promise<T> => new Promise((r) => setTimeout(() => r(v), ms));

export const mockApi: CApi = {
  login: (req) =>
    wait({
      cUserNo: db.profile.cUserNo, token: `mock-c-token-${Date.now()}`, expireIn: 604800,
      profile: { ...db.profile, phone: req.phone ?? db.profile.phone },
    }, 350),

  getProfile: () => wait(db.profile),
  getWallet: () => wait(db.wallet),

  listNearby: (q: PageQ = {}) =>
    wait(db.paginate(db.nearby, q.page, q.size ?? 20,
      (c) => !q.keyword || `${c.siteName}${c.address}`.toLowerCase().includes(q.keyword.toLowerCase()))),

  rent: (cabinetNo) => {
    const no = `ORD${600000 + db.orders.length}`;
    const site = db.nearby.find((c) => c.cabinetNo === cabinetNo)?.siteName ?? "网点";
    const order: RentOrder = {
      orderNo: no, cUserNo: db.profile.cUserNo, cabinetNo, returnCabinetNo: null,
      powerbankNo: `PB${9000 + db.orders.length}`, siteName: site, status: "IN_USE",
      rentStartAt: new Date().toISOString(), rentEndAt: null, durationMin: null,
      feeAmount: 0, depositAmount: 50, currency: "AED",
    };
    db.orders.unshift(order);
    return wait({ orderNo: no }, 600);
  },

  getOrder: (no) => {
    const o = db.orders.find((x) => x.orderNo === no);
    if (!o) return Promise.reject(new Error("订单不存在"));
    return wait(o);
  },

  listOrders: (q: OrderQ = {}) =>
    wait(db.paginate(db.orders, q.page, q.size,
      (o) => (!q.status || o.status === q.status) &&
        (!q.keyword || `${o.orderNo}${o.siteName}`.toLowerCase().includes(q.keyword.toLowerCase())))),

  returnOrder: (no) => {
    const o = db.orders.find((x) => x.orderNo === no);
    if (!o) return Promise.reject(new Error("订单不存在"));
    const start = o.rentStartAt ? new Date(o.rentStartAt).getTime() : Date.now();
    const dur = Math.max(1, Math.round((Date.now() - start) / 60000));
    o.status = "SETTLED"; o.durationMin = dur; o.rentEndAt = new Date().toISOString();
    o.returnCabinetNo = o.cabinetNo; o.feeAmount = Math.min(30, Math.ceil(dur / 30) * 3);
    return wait({ ...o }, 500);
  },

  report: (_req) => wait({ ok: true as const, reportNo: `RP${Date.now()}` }, 400),
};
