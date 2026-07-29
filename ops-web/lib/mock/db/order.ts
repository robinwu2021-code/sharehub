// 订单域：租借订单 orders（全库订单号来源）/ 异常订单 orderExceptions / 押金记录 depositRecords /
// 预约订单 reservations / 免费订单 freeOrders。
// 机柜号引用 device.ts，站点引用 location.ts；售后（投诉/退款）在 cs.ts 里反向引用本域的 orders。
import type {
  RentOrder, OrderStatus, OrderException, DepositRecord,
  Reservation, FreeOrder, FreeOrderStats, PageQuery,
} from "../../types";
import { NICKS, p, iso } from "./internal";
import { paginate, kwHit } from "./helpers";
import { cabinets, cabNo, powerbanks } from "./device";
import { sites } from "./location";
import { cUsers, freeWhitelist } from "./user";

// —— 订单 ——
// 充电宝号引用 device.ts 的 powerbanks（原先自造 PB1000+ 这套号，在充电宝档案里查无此宝）；
// 点位名直接取所在机柜的 locationName，保证「订单 → 机柜 → 站点」三页显示同一个地方。
const OSTATUS: OrderStatus[] = ["IN_USE", "SETTLED", "CLOSED", "RETURNED", "EXCEPTION", "CREATED"];
export const orders: RentOrder[] = Array.from({ length: 120 }, (_, i) => {
  const st = p(OSTATUS, i);
  const dur = st === "IN_USE" || st === "CREATED" ? null : 20 + ((i * 17) % 300);
  const cab = p(cabinets, i);
  return {
    orderNo: `ORD${500000 + i}`, cUserNo: `U${3000 + (i % 40)}`, cabinetNo: cab.cabinetNo,
    returnCabinetNo: st === "SETTLED" || st === "CLOSED" ? p(cabinets, i + 3).cabinetNo : null,
    powerbankNo: p(powerbanks, i).powerbankNo, locationName: cab.locationName,
    status: st, rentStartAt: iso(i * 3600_000),
    rentEndAt: dur ? iso(i * 3600_000 - dur * 60000) : null, durationMin: dur,
    feeAmount: dur ? Math.min(30, Math.ceil(dur / 30) * 3) : 0, depositAmount: 50, currency: "AED",
  };
});

// —— 异常订单 ——
// 就是 orders 里 status=EXCEPTION 的那 20 单（原先自造 ORD520000+ 号段，在订单列表里搜不到）。
const EXCEPTION_TYPES: OrderException["type"][] = ["NOT_EJECTED", "NOT_RETURNED", "OVERTIME_BUYOUT", "DOUBLE_CHARGE"];
export const orderExceptions: OrderException[] = orders
  .filter((o) => o.status === "EXCEPTION")
  .map((o, i) => ({
    orderNo: o.orderNo, type: p(EXCEPTION_TYPES, i),
    cabinetNo: o.cabinetNo, userNo: o.cUserNo, amount: Number((3 + (i * 7) % 97).toFixed(2)),
    currency: "AED", status: i % 3 === 0 ? "HANDLED" : "OPEN", createdAt: iso(i * 5400_000),
  }));

// —— 押金记录（PDF 对照新增 mock）——
// 押金随订单产生，因此订单号/用户号一律取自 orders（原先是 ORD10500+ / U1000+ 两套孤立号段）。
const DEP_STATUS: DepositRecord["status"][] = ["HELD", "RELEASED", "BOUGHT_OUT", "ARREARS"];
export const depositRecords: DepositRecord[] = Array.from({ length: 26 }, (_, i) => {
  const status = p(DEP_STATUS, i);
  const o = orders[i * 3]; // 26 条铺开在前 78 单里，订单号互不重复
  return {
    depositNo: `DEP${900 + i}`, orderNo: o.orderNo, userNo: o.cUserNo,
    amount: p([49, 99, 99, 199], i), currency: "AED", status,
    arrearsAmount: status === "ARREARS" ? p([12, 25, 40, 8], i) : 0, createdAt: iso(i * 43200_000),
  };
});

// —— §3 预约订单：预约取宝 / 预约还位 ——
const RES_STATUS: Reservation["status"][] = ["PENDING", "PENDING", "FULFILLED", "EXPIRED", "CANCELLED", "FULFILLED"];
export const reservations: Reservation[] = Array.from({ length: 22 }, (_, i) => {
  const st = p(RES_STATUS, i);
  const site = p(sites, i);
  const type: Reservation["type"] = i % 3 === 0 ? "RETURN" : "BORROW";
  // 待履约的预约窗口必须挂在**真实当前时间**上（而非 mock 固定时间轴），
  // 否则「即将超时」永远算不出来——该高亮判定的是「距 reservedTo 还剩多久」。
  const now = Date.now();
  const pendingFrom = new Date(now - 10 * 60_000).toISOString();
  const pendingTo = new Date(now + (i < 2 ? 12 : 45 + i * 20) * 60_000).toISOString();
  const fromOffset = (i * 6 + 3) * 3600_000;
  if (st === "PENDING") {
    return {
      reservationNo: `RSV${600 + i}`,
      userNo: `U${3000 + (i % 40)}`,
      type,
      siteNo: site.siteNo,
      siteName: site.name,
      cabinetNo: i % 4 === 0 ? null : cabNo(i),
      reservedFrom: pendingFrom,
      reservedTo: pendingTo,
      holdFee: 0,
      currency: "AED",
      status: st,
      orderNo: null,
    };
  }
  return {
    reservationNo: `RSV${600 + i}`,
    userNo: `U${3000 + (i % 40)}`,
    type,
    siteNo: site.siteNo,
    siteName: site.name,
    cabinetNo: i % 4 === 0 ? null : cabNo(i), // 空 = 站点级预约（不指定机柜）
    reservedFrom: iso(fromOffset),
    reservedTo: iso(fromOffset - 30 * 60_000),
    holdFee: st === "EXPIRED" ? 2 + (i % 3) : 0,
    currency: "AED",
    status: st,
    orderNo: st === "FULFILLED" ? `ORD${500000 + (i % 120)}` : null,
  };
});

// —— §4 免费订单：来源即白名单用途，页头做成本管控统计 ——
// 每条都是**真实存在的一笔已结束租借订单**（原先自造 ORD500200+ 号段，在订单列表里搜不到），
// 且下单人必须真的在免费白名单里 —— 免费的理由（whitelistReason）直接取自该用户的白名单记录。
// 减免金额 = 该订单本应收取的费用，时长/机柜/站点也全部沿用订单，点进订单详情能一一对上。
const FREE_WL = new Map(freeWhitelist.map((w) => [w.userNo, w]));
const FREE_SOURCE_ORDERS = orders
  .filter((o) => o.durationMin !== null && FREE_WL.has(o.cUserNo))
  .slice(0, 26);
export const freeOrders: FreeOrder[] = FREE_SOURCE_ORDERS.map((o, i) => {
  const start = i * 20 * 3600_000;
  return {
    orderNo: o.orderNo,
    userNo: o.cUserNo,
    nickname: cUsers.find((u) => u.cUserNo === o.cUserNo)?.nickname ?? p(NICKS, i),
    whitelistReason: FREE_WL.get(o.cUserNo)!.reason,
    waivedAmount: o.feeAmount,
    currency: "AED",
    // 类型上 RentOrder.locationName 可空，但 mock 的机柜一律带点位名（见 device.ts cabinets）
    siteName: o.locationName!,
    cabinetNo: o.cabinetNo,
    startedAt: iso(start),
    endedAt: iso(start - (o.durationMin ?? 0) * 60_000),
    duration: o.durationMin ?? 0,
  };
});

// —— list ——
export const listOrderExceptions = (q: PageQuery = {}) => paginate(orderExceptions, q.page, q.size, (x) => kwHit(q.keyword, x.orderNo, x.cabinetNo, x.userNo));
export const listDepositRecords = (q: PageQuery = {}) => paginate(depositRecords, q.page, q.size, (x) => kwHit(q.keyword, x.depositNo, x.orderNo, x.userNo));

export const listReservations = (q: PageQuery & { status?: string; type?: string } = {}) =>
  paginate(reservations, q.page, q.size, (x) => {
    if (!kwHit(q.keyword, x.reservationNo, x.userNo, x.siteName, x.cabinetNo, x.orderNo)) return false;
    if (q.status && x.status !== q.status) return false;
    if (q.type && x.type !== q.type) return false;
    return true;
  });
/** 取消预约：仅 PENDING 可取消（非 PENDING 直接原样返回，由前端按钮先行拦截）。 */
export const cancelReservation = (no: string): Reservation => {
  const i = reservations.findIndex((r) => r.reservationNo === no);
  if (i < 0) throw new Error(`预约不存在：${no}`);
  if (reservations[i].status !== "PENDING") throw new Error("仅待履约（PENDING）的预约可取消");
  reservations[i] = { ...reservations[i], status: "CANCELLED", holdFee: 0 };
  return reservations[i];
};

export const listFreeOrders = (q: PageQuery & { reason?: string } = {}) =>
  paginate(freeOrders, q.page, q.size, (x) => {
    if (!kwHit(q.keyword, x.orderNo, x.userNo, x.nickname, x.siteName, x.cabinetNo)) return false;
    if (q.reason && x.whitelistReason !== q.reason) return false;
    return true;
  });
/** 页头统计：本月免费单数（按 mock「当前」月份口径）+ 累计减免金额。 */
export const getFreeOrderStats = (): FreeOrderStats => {
  const month = iso(0).slice(0, 7);
  return {
    monthCount: freeOrders.filter((o) => o.startedAt.slice(0, 7) === month).length,
    waivedTotal: freeOrders.reduce((s, o) => s + o.waivedAmount, 0),
    currency: "AED",
  };
};
