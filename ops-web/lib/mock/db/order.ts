// 订单域：租借订单 orders（全库订单号来源）/ 异常订单 orderExceptions / 押金记录 depositRecords /
// 预约订单 reservations / 免费订单 freeOrders。
// 机柜号引用 device.ts，站点引用 location.ts；售后（投诉/退款）在 cs.ts 里反向引用本域的 orders。
import type {
  RentOrder, OrderStatus, OrderException, OrderExceptionStatus,
  ExceptionHandleAction, OrderExceptionHandlePayload,
  DepositRecord, DepositStatus, DepositAction,
  DepositBuyoutPayload, ArrearsDunPayload,
  OrderIntervention, OrderInterventionAction, OrderIntervenePayload, OrderInterveneResult, OrderEvent,
  Reservation, FreeOrder, FreeOrderStats, PageQuery, WorkOrderType,
} from "../../types";
import {
  ORDER_INTERVENTIONS, canIntervene, DEPOSIT_TRANSITIONS, canDepositAction,
  EXCEPTION_HANDLINGS, canHandleException,
} from "../../types";
import { NICKS, p, iso } from "./internal";
import { notFound, fail } from "@/lib/biz-error";
import { paginate, kwHit, nextNo } from "./helpers";
import { cabinets, cabNo, powerbanks } from "./device";
import { sites } from "./location";
import { cUsers, freeWhitelist } from "./user";
import { createWorkOrder } from "./workorder";

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
// 全部种子一律落 PENDING —— 已处置/处置中的样例在文件末尾**走真实处置入口**生成，
// 保证「状态 ↔ 处置留痕（handledBy/handledAt/handleResult/工单号/退款号）」天然一致。
export const orderExceptions: OrderException[] = orders
  .filter((o) => o.status === "EXCEPTION")
  .map((o, i) => ({
    // 异常单有自己的编号：一个订单可以有多条异常，不能拿 orderNo 当键
    exceptionNo: `OEX${7000 + i}`,
    orderNo: o.orderNo, type: p(EXCEPTION_TYPES, i),
    cabinetNo: o.cabinetNo, userNo: o.cUserNo, amount: Number((3 + (i * 7) % 97).toFixed(2)),
    currency: "AED", status: "PENDING" as OrderExceptionStatus, createdAt: iso(i * 5400_000),
    handleAction: null, handleResult: null, handledBy: null, handledAt: null,
    workOrderNo: null, refundNo: null,
  }));

// —— 押金记录（PDF 对照新增 mock）——
// 押金随订单产生，因此订单号/用户号一律取自 orders（原先是 ORD10500+ / U1000+ 两套孤立号段）。
const DEP_STATUS: DepositStatus[] = ["HELD", "RELEASED", "BOUGHT_OUT", "ARREARS"];
export const depositRecords: DepositRecord[] = Array.from({ length: 26 }, (_, i) => {
  const status = p(DEP_STATUS, i);
  const o = orders[i * 3]; // 26 条铺开在前 78 单里，订单号互不重复
  const amount = p([49, 99, 99, 199], i);
  return {
    depositNo: `DEP${900 + i}`, orderNo: o.orderNo, userNo: o.cUserNo,
    amount, currency: "AED", status,
    arrearsAmount: status === "ARREARS" ? p([12, 25, 40, 8], i) : 0, createdAt: iso(i * 43200_000),
    // 终态记录必须带处置留痕，否则「已解冻/已买断」在页面上查不到是谁什么时候处置的
    releasedAt: status === "RELEASED" ? iso(i * 43200_000 - 7200_000) : null,
    buyoutAmount: status === "BOUGHT_OUT" ? amount : null,
    buyoutAt: status === "BOUGHT_OUT" ? iso(i * 43200_000 - 10800_000) : null,
    dunCount: 0, lastDunAt: null, lastDunChannel: null,
    operatorName: status === "HELD" || status === "ARREARS" ? null : "admin",
    note: status === "RELEASED" ? "订单已结清，自动解冻" : status === "BOUGHT_OUT" ? "超时未归还，押金转买断" : null,
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
// 搜索域随可见列扩展（规格 §17.1-9）：处置留痕上了列表，工单号/退款号/处置人也要能搜到。
export const listOrderExceptions = (q: PageQuery & { status?: string; type?: string } = {}) =>
  paginate(orderExceptions, q.page, q.size, (x) => {
    if (q.status && x.status !== q.status) return false;
    if (q.type && x.type !== q.type) return false;
    return kwHit(q.keyword, x.orderNo, x.cabinetNo, x.userNo, x.workOrderNo, x.refundNo, x.handledBy);
  });
export const listDepositRecords = (q: PageQuery & { status?: string } = {}) =>
  paginate(depositRecords, q.page, q.size, (x) =>
    kwHit(q.keyword, x.depositNo, x.orderNo, x.userNo) && (!q.status || x.status === q.status));

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
  if (i < 0) throw notFound("预约", "Reservation", no);
  if (reservations[i].status !== "PENDING") throw fail("仅待履约（PENDING）的预约可取消", "Only a PENDING reservation can be cancelled", "يمكن إلغاء الحجز فقط وهو قيد الانتظار (PENDING)");
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

// ————————————————————————————————————————————————————————————————
// 订单人工干预（S1：修 F0 伪实现）
//
// 修之前：`interveneOrder` 在 mock 层只 `wait({ok:true})` —— eject / force_return /
// waive / compensate 四个动作点了**订单状态一点不变**，是全站最容易误导使用者的地方。
// 现在：状态机定义在 lib/types/order.ts（页面按钮与本层共用），本层负责**强制执行**，
// 并且每次干预都落一条不可省略的干预记录（审计刚需，也是「真落库」的证据）。
// ————————————————————————————————————————————————————————————————
const now = () => iso(0); // mock 统一时间轴（与 workorder.ts 同口径，勿用真实 Date.now）

/** 干预记录（审计）。最新在前。 */
export const orderInterventions: OrderIntervention[] = [];

const IV_LABEL: Record<OrderInterventionAction, string> = {
  eject: "远程弹出", force_return: "强制归还", waive: "免单", compensate: "补偿", refund_apply: "申请退款",
};

/** 状态机违规 / 必填缺失。页面侧由全局 MutationCache.onError 统一弹 notify.error。 */
export class OrderInterventionError extends Error {
  constructor(readonly orderNo: string, readonly action: OrderInterventionAction, readonly from: OrderStatus | null, msg?: string) {
    super(msg ?? `订单 ${orderNo} 当前状态「${from}」不允许执行「${IV_LABEL[action]}」`);
    this.name = "OrderInterventionError";
  }
}

const findOrder = (orderNo: string) => orders.find((o) => o.orderNo === orderNo);
/** 计费口径与订单种子数据完全一致（每 30 分钟 3 元，单笔上限 30）。 */
const feeOf = (durationMin: number) => Math.min(30, Math.ceil(durationMin / 30) * 3);

/**
 * 统一干预入口：查单 → 校验状态机与必填 → 落业务副作用 → 迁状态 → 写干预记录。
 * 所有干预动作都必须走这里，不允许任何地方直接改 `o.status`。
 *
 * 注：`refund_apply` 的退款申请由 API mock 层先调 `applyRefund` 再把金额传进来 ——
 * 退款记录住在 cs.ts，而 cs.ts 反向依赖本文件的 `orders`，直接 import 会形成
 * 循环依赖（cs.ts 模块体在 orders 初始化前就会读它）。
 */
export function interveneOrder(
  orderNo: string, action: OrderInterventionAction, payload: OrderIntervenePayload,
): OrderInterveneResult {
  const o = findOrder(orderNo);
  if (!o) throw new OrderInterventionError(orderNo, action, null, `订单 ${orderNo} 不存在`);
  const reason = payload.reason?.trim();
  // 干预必须填原因（沿用退款审批口径）：无原因的状态/资金干预无从追责
  if (!reason) throw new OrderInterventionError(orderNo, action, o.status, `「${IV_LABEL[action]}」必须填写干预原因`);
  if (!canIntervene(o.status, action)) throw new OrderInterventionError(orderNo, action, o.status);

  const beforeStatus = o.status;
  let amount: number | null = null;

  switch (action) {
    case "eject":
      // 远程弹出：记一次弹出并置「弹出中」，等柜机上报后才会变 IN_USE
      o.ejectCount = (o.ejectCount ?? 0) + 1;
      o.lastEjectAt = now();
      break;
    case "force_return": {
      // 强制归还：按 mock 当前时间结单，写结束时间 + 时长 + 费用；归还柜机缺省记为借出柜机
      const start = o.rentStartAt ? new Date(o.rentStartAt).getTime() : new Date(now()).getTime();
      const dur = Math.max(1, Math.round((new Date(now()).getTime() - start) / 60_000));
      o.rentEndAt = now();
      o.durationMin = dur;
      o.feeAmount = feeOf(dur);
      o.returnCabinetNo = o.returnCabinetNo ?? o.cabinetNo;
      amount = o.feeAmount;
      break;
    }
    case "waive": {
      // 免单：应收置 0，减免额单独记账（成本管控要看得见免了多少）
      if (o.feeAmount <= 0) throw new OrderInterventionError(orderNo, action, o.status, "该订单应收金额已为 0，无需免单");
      amount = o.feeAmount;
      o.waivedAmount = Number(((o.waivedAmount ?? 0) + o.feeAmount).toFixed(2));
      o.feeAmount = 0;
      break;
    }
    case "compensate": {
      // 补偿：mock 口径为**补至用户余额**（另一种可选口径是发券，与优惠券域耦合，此处不选）
      const amt = Number(payload.amount ?? 0);
      if (!(amt > 0)) throw new OrderInterventionError(orderNo, action, o.status, "补偿金额必须大于 0");
      o.compensateAmount = Number(((o.compensateAmount ?? 0) + amt).toFixed(2));
      amount = amt;
      break;
    }
    case "refund_apply":
      // 只留痕：真正的退款申请由调用方（API mock 层）落到退款审批队列
      amount = payload.amount ?? o.feeAmount;
      break;
  }

  const to = ORDER_INTERVENTIONS[action].to;
  if (to) {
    o.status = to;
    // 只在**状态真的变了**时写流水：to 为 null 的干预（免单/补偿/退款申请）
    // 改的是钱不是状态，它没有一条状态流转可记 —— 那些留在干预历史里。
    appendOrderEvent(orderNo, beforeStatus, to, action.toUpperCase(), payload.operatorName);
  }

  const intervention: OrderIntervention = {
    interventionNo: nextNo("OIV", orderInterventions, 90000, "interventionNo"),
    orderNo, action, operatorName: payload.operatorName?.trim() || "admin", reason,
    amount, currency: o.currency, beforeStatus, afterStatus: o.status, createdAt: now(),
  };
  orderInterventions.unshift(intervention);
  return { order: { ...o }, intervention };
}

// ============================================================================
// 订单状态流转留痕（ord_event_log）
// ----------------------------------------------------------------------------
// 后端四个 service（退款/押金/异常/投诉）一直在往这张表写。mock 若不写，
// 时间线在 mock 下永远是空的 —— 而**空时间线与「功能没做」长得一模一样**，
// 下一个人会以为没接通，然后去重做一遍。
// ============================================================================

/** append-only：只插不改。时间正序（与后端 timeline 一致）。 */
export const orderEvents: OrderEvent[] = [];

/** 追加一条流水。`from` 为空表示建单那一条。 */
export function appendOrderEvent(
  orderNo: string, fromStatus: string | null, toStatus: string, event: string, operator?: string | null,
): void {
  orderEvents.push({
    orderNo, fromStatus, toStatus, event,
    operator: operator?.trim() || "admin", createdAt: now(),
  });
}

/**
 * 按订单号取时间线，**时间正序**（与后端 `OrderEventLogService.timeline` 一致）。
 * 不分页：一张订单的事件是有界的。
 */
export const listOrderEvents = (orderNo: string): OrderEvent[] =>
  orderEvents.filter((x) => x.orderNo === orderNo);

/** 干预记录查询：`orderNo` 精确过滤（订单详情抽屉的时间线），keyword 覆盖号/人/原因。 */
export const listOrderInterventions = (q: PageQuery & { orderNo?: string; action?: string } = {}) =>
  paginate(orderInterventions, q.page, q.size, (x) => {
    if (q.orderNo && x.orderNo !== q.orderNo) return false;
    if (q.action && x.action !== q.action) return false;
    return kwHit(q.keyword, x.interventionNo, x.orderNo, x.operatorName, x.reason);
  });

// ————————————————————————————————————————————————————————————————
// 押金与欠费处置（S1：F1 → F3）
// 状态机同样定义在 lib/types/order.ts，本层强制执行——非法迁移抛错。
// ————————————————————————————————————————————————————————————————
const DEP_LABEL: Record<DepositAction, string> = { release: "解冻", buyout: "买断", dun: "催缴" };

export class DepositTransitionError extends Error {
  constructor(readonly depositNo: string, readonly action: DepositAction, readonly from: DepositStatus | null, msg?: string) {
    super(msg ?? `押金单 ${depositNo} 当前状态「${from}」不允许执行「${DEP_LABEL[action]}」`);
    this.name = "DepositTransitionError";
  }
}

const findDeposit = (depositNo: string) => depositRecords.find((d) => d.depositNo === depositNo);

/** 统一迁移入口：查单 → 校验合法性 → 打补丁 → 落状态。禁止任何地方直接写 `d.status`。 */
export function transitionDeposit(depositNo: string, action: DepositAction, patch: Partial<DepositRecord> = {}): DepositRecord {
  const d = findDeposit(depositNo);
  if (!d) throw new DepositTransitionError(depositNo, action, null, `押金单 ${depositNo} 不存在`);
  if (!canDepositAction(d.status, action)) throw new DepositTransitionError(depositNo, action, d.status);
  Object.assign(d, patch, { status: DEPOSIT_TRANSITIONS[action].to });
  return d;
}

/** 解冻：HELD → RELEASED（原因必填；重复解冻＝重复出款，故终态不可再解冻）。 */
export const releaseDeposit = (depositNo: string, reason: string): DepositRecord => {
  if (!reason?.trim()) throw new DepositTransitionError(depositNo, "release", findDeposit(depositNo)?.status ?? null, "解冻必须填写原因");
  return transitionDeposit(depositNo, "release", { releasedAt: now(), operatorName: "admin", note: reason.trim() });
};

/** 买断：HELD → BOUGHT_OUT（金额与原因必填；买断金额不得超过押金额）。 */
export const buyoutDeposit = (depositNo: string, x: DepositBuyoutPayload): DepositRecord => {
  const d = findDeposit(depositNo);
  const amt = Number(x?.amount ?? 0);
  if (!(amt > 0)) throw new DepositTransitionError(depositNo, "buyout", d?.status ?? null, "买断金额必须大于 0");
  if (d && amt > d.amount) throw new DepositTransitionError(depositNo, "buyout", d.status, `买断金额不得超过押金额 ${d.amount}`);
  if (!x?.reason?.trim()) throw new DepositTransitionError(depositNo, "buyout", d?.status ?? null, "买断必须填写原因");
  return transitionDeposit(depositNo, "buyout", {
    buyoutAmount: amt, buyoutAt: now(), operatorName: "admin", note: x.reason.trim(),
  });
};

/** 催缴：ARREARS 记一次催缴（次数 +1、最后催缴时间/渠道），**状态不变**。 */
export const dunArrears = (depositNo: string, x: ArrearsDunPayload): DepositRecord => {
  const d = findDeposit(depositNo);
  if (!x?.channel) throw new DepositTransitionError(depositNo, "dun", d?.status ?? null, "催缴必须选择催缴渠道");
  return transitionDeposit(depositNo, "dun", {
    dunCount: (d?.dunCount ?? 0) + 1, lastDunAt: now(), lastDunChannel: x.channel,
    operatorName: "admin", note: x.note?.trim() || `已通过${x.channel}催缴`,
  });
};

// ————————————————————————————————————————————————————————————————
// 异常订单处置（S2：F2 → F3，权限码 order:exception:handle 早已定义、页面从没用过）
//
// 修之前：/orders?tab=exceptions 只有一张只读列表 —— 看得见异常、处置不了，
// 「已处理」的单也说不清是谁在什么时候按什么结论处理的。
// 现在：三种处置方式（转工单 / 发起退款 / 直接关闭）走状态机，结论必填，全部留痕。
// ————————————————————————————————————————————————————————————————
const EXC_LABEL: Record<ExceptionHandleAction, string> = {
  work_order: "转工单", refund: "发起退款", close: "直接关闭",
};

/** 异常类型 → 工单类型：设备侧异常交运维（FAULT），计费/归还争议交客服闭环（COMPLAINT）。 */
const EXC_WO_TYPE: Record<OrderException["type"], WorkOrderType> = {
  NOT_EJECTED: "FAULT",
  NOT_RETURNED: "FAULT",
  OVERTIME_BUYOUT: "COMPLAINT",
  DOUBLE_CHARGE: "COMPLAINT",
};
const EXC_TYPE_TEXT: Record<OrderException["type"], string> = {
  NOT_EJECTED: "未弹出", NOT_RETURNED: "未归还", OVERTIME_BUYOUT: "超时买断", DOUBLE_CHARGE: "重复扣款",
};

export class OrderExceptionError extends Error {
  constructor(readonly orderNo: string, readonly action: ExceptionHandleAction, readonly from: OrderExceptionStatus | null, msg?: string) {
    super(msg ?? `异常单 ${orderNo} 当前状态「${from}」不允许执行「${EXC_LABEL[action]}」`);
    this.name = "OrderExceptionError";
  }
}

const findException = (orderNo: string) => orderExceptions.find((e) => e.orderNo === orderNo);

/**
 * 统一处置入口：查单 → 校验状态机与必填 → 落下游动作（工单/退款）→ 迁状态 → 记留痕。
 * 禁止任何地方直接改 `e.status`。
 *
 * 注：`refund` 的退款申请由 API mock 层先调 `applyRefund` 再把 `refundNo` 传进来
 * （退款记录住在 cs.ts，cs.ts 反向依赖本文件的 orders，直接 import 会成环）。
 */
export function handleOrderException(
  orderNo: string, action: ExceptionHandleAction, payload: OrderExceptionHandlePayload,
): OrderException {
  const e = findException(orderNo);
  if (!e) throw new OrderExceptionError(orderNo, action, null, `异常单 ${orderNo} 不存在`);
  const result = payload?.result?.trim();
  if (!result) throw new OrderExceptionError(orderNo, action, e.status, `「${EXC_LABEL[action]}」必须填写处置结论`);
  if (!canHandleException(e.status, action)) throw new OrderExceptionError(orderNo, action, e.status);

  if (action === "work_order") {
    // 同一异常单不重复开单：重复转工单会让运维收到两条同样的活
    if (e.workOrderNo) throw new OrderExceptionError(orderNo, action, e.status, `该异常单已转工单 ${e.workOrderNo}，不可重复转`);
    const wo = createWorkOrder({
      type: EXC_WO_TYPE[e.type],
      source: "USER",
      sourceNo: e.orderNo,
      priority: "HIGH", // 异常单已经产生资损/投诉风险，一律高优
      cabinetNo: e.cabinetNo,
      description: `异常订单 ${e.orderNo}（${EXC_TYPE_TEXT[e.type]}）：${result}`,
    });
    e.workOrderNo = wo.woNo;
  }
  if (action === "refund") {
    // 同一异常单不重复退款：真正出款在退款审批队列，这里只保证申请唯一
    if (e.refundNo) throw new OrderExceptionError(orderNo, action, e.status, `该异常单已发起退款申请 ${e.refundNo}，不可重复发起`);
    if (!payload.refundNo) throw new OrderExceptionError(orderNo, action, e.status, "退款申请号缺失（应由 API 层先落退款申请再回填）");
    e.refundNo = payload.refundNo;
  }

  const exBefore = e.status;
  e.status = EXCEPTION_HANDLINGS[action].to;
  appendOrderEvent(orderNo, exBefore, e.status, `EXCEPTION_${action.toUpperCase()}`, payload.operatorName);
  e.handleAction = action;
  e.handleResult = result;
  e.handledBy = payload.operatorName?.trim() || "admin";
  e.handledAt = now();
  return { ...e };
}

// —— 定点演示数据：**直接走干预入口生成**，保证「干预记录 ↔ 订单状态」天然一致 ——
// （手写记录必然与订单状态对不上，那正是本次要修的 F0 病根）
interveneOrder(orders[23].orderNo, "eject", {
  reason: "用户反馈扫码后充电宝未弹出，远程补弹一次", operatorName: "Sara Ahmed",
});
interveneOrder(orders[18].orderNo, "force_return", {
  reason: "宝已在其他柜机归还但柜机未上报，人工结单", operatorName: "Omar Khan",
});
interveneOrder(orders[4].orderNo, "waive", {
  reason: "柜机故障导致无法归还，本单免单", operatorName: "admin",
});
interveneOrder(orders[4].orderNo, "compensate", {
  reason: "同一异常单额外补偿用户余额", amount: 5, operatorName: "admin",
});

// —— 异常单定点演示数据：同样**走真实处置入口**（转工单会真的落一条工单）——
// 「发起退款」不在此处种：退款申请要先落 cs.ts 的退款队列，db 层不能反向 import，
// 该链路由 API mock 层组合（见 lib/api/mocks/order.ts），页面上点一次即可验证。
handleOrderException(orderExceptions[0].orderNo, "close", {
  result: "核查柜机日志确认宝已正常弹出，用户未取走，无资损，直接关闭", operatorName: "Sara Ahmed",
});
handleOrderException(orderExceptions[1].orderNo, "work_order", {
  result: "柜机仓位卡宝导致未弹出，转运维现场检修", operatorName: "Omar Khan",
});
