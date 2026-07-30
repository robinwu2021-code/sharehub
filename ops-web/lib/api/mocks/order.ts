// 覆盖范围：租借订单与人工干预、异常订单、押金流水、售后处置（投诉 / 退款审批）、
// 预约订单、免费订单及其统计。
import * as db from "../../mock/db";
import type { OrderApi } from "../contracts/order";
import type { PageQ, OrderQ, StatusQ, ReservationQ, FreeOrderQ } from "../query";
import { wait } from "./_wait";

export const orderMock: OrderApi = {
  listOrders: (q: OrderQ = {}) =>
    wait(db.paginate(db.orders, q.page, q.size, (o) =>
      db.kwHit(q.keyword, o.orderNo, o.cUserNo) && (!q.status || o.status === q.status))),
  getOrder: (no) => wait(db.orders.find((o) => o.orderNo === no)!),
  // 四个干预动作在 db 层真改订单状态并落干预记录（见 mock/db/order.ts 的状态机）。
  // 「申请退款」不是终态动作：先落一条 PENDING 退款申请（进 /orders?tab=refunds 审批队列），
  // 再把退款金额带进干预记录 —— 退款记录住在 cs.ts，db 层不能反向 import，故在此组合。
  interveneOrder: (no, action, payload) => {
    if (action === "refund_apply") {
      const r = db.applyRefund(no, payload.reason?.trim() || "客服代客申请退款");
      return wait(db.interveneOrder(no, action, { ...payload, amount: r.amount }), 400);
    }
    return wait(db.interveneOrder(no, action, payload), 400);
  },
  listOrderInterventions: (q: PageQ = {}) => wait(db.listOrderInterventions(q)),

  // 订单扩展
  listOrderExceptions: (q: PageQ = {}) => wait(db.listOrderExceptions(q)),
  listDepositRecords: (q: StatusQ = {}) => wait(db.listDepositRecords(q)),
  releaseDeposit: (no, reason) => wait(db.releaseDeposit(no, reason), 400),
  buyoutDeposit: (no, payload) => wait(db.buyoutDeposit(no, payload), 400),
  dunArrears: (no, payload) => wait(db.dunArrears(no, payload), 400),

  // 售后处置
  listOrderComplaints: (q: StatusQ = {}) => wait(db.listOrderComplaints(q)),
  handleOrderComplaint: (no, resolution, note) => wait(db.handleOrderComplaint(no, resolution, note), 400),
  raiseComplaintWorkOrder: (no) => wait(db.raiseComplaintWorkOrder(no), 400),
  listRefundRecords: (q: StatusQ = {}) => wait(db.listRefundRecords(q)),
  auditRefund: (no, approve, rejectReason) => wait(db.auditRefund(no, approve, rejectReason), 400),

  // 批次 B4：预约订单 / 免费订单
  listReservations: (q: ReservationQ = {}) => wait(db.listReservations(q)),
  cancelReservation: (no) => wait(db.cancelReservation(no), 400),
  listFreeOrders: (q: FreeOrderQ = {}) => wait(db.listFreeOrders(q)),
  getFreeOrderStats: () => wait(db.getFreeOrderStats()),
};
