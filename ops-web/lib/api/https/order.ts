// 覆盖范围：租借订单与人工干预、异常订单、押金流水、售后处置（投诉 / 退款审批）、
// 预约订单、免费订单及其统计。
// 端点前缀：交易主链路 /api/trade/**，售后与押金 /api/trade/**（沿用现状，未改动）。
import { client } from "../http-client";
import type { OrderApi } from "../contracts/order";
import type { PageQ, OrderQ, StatusQ, ReservationQ, FreeOrderQ } from "../query";

export const orderHttp: OrderApi = {
  listOrders: (q?: OrderQ) => client.get("/api/trade/orders", q),
  getOrder: (no) => client.get(`/api/trade/orders/${no}`),
  interveneOrder: (no, action) => client.post(`/api/trade/orders/${no}/intervene`, { action }),

  // 订单扩展
  listOrderExceptions: (q?: PageQ) => client.get("/api/trade/order-exceptions", q),
  listDepositRecords: (q?: PageQ) => client.get("/api/trade/deposits", q),

  // 售后处置
  listOrderComplaints: (q?: StatusQ) => client.get("/api/trade/complaints", q),
  handleOrderComplaint: (no, resolution, note) => client.post(`/api/trade/complaints/${no}/handle`, { resolution, note }),
  raiseComplaintWorkOrder: (no) => client.post(`/api/trade/complaints/${no}/work-order`, {}),
  listRefundRecords: (q?: StatusQ) => client.get("/api/trade/refunds", q),
  auditRefund: (no, approve, rejectReason) => client.post(`/api/trade/refunds/${no}/audit`, { approve, rejectReason }),

  // 批次 B4：预约与免费订单归 trade 域
  listReservations: (q?: ReservationQ) => client.get("/api/trade/reservations", q),
  cancelReservation: (no) => client.post(`/api/trade/reservations/${no}/cancel`, {}),
  listFreeOrders: (q?: FreeOrderQ) => client.get("/api/trade/free-orders", q),
  getFreeOrderStats: () => client.get("/api/trade/free-orders/stats"),
};
