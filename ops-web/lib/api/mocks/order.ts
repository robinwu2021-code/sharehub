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
  // 「申请退款」不是终态动作：落一条 PENDING 退款申请，进 /orders?tab=refunds 审批队列
  interveneOrder: (no, action) => {
    if (action === "refund_apply") db.applyRefund(no);
    return wait({ ok: true } as const, 400);
  },

  // 订单扩展
  listOrderExceptions: (q: PageQ = {}) => wait(db.listOrderExceptions(q)),
  listDepositRecords: (q: PageQ = {}) => wait(db.listDepositRecords(q)),

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
