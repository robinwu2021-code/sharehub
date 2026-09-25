// 覆盖范围：租借订单与人工干预、异常订单、押金流水、售后处置（投诉 / 退款审批）、
// 预约订单、免费订单及其统计。
// 端点前缀：交易主链路 /api/trade/**，售后与押金 /api/trade/**（沿用现状，未改动）。
import { client } from "../http-client";
import type { OrderApi } from "../contracts/order";
import type { PageQ, OrderQ, StatusQ, ReservationQ, FreeOrderQ } from "../query";

export const orderHttp: OrderApi = {
  listOrders: (q?: OrderQ) => client.get("/api/trade/orders", q),
  getOrder: (no) => client.get(`/api/trade/orders/${no}`),
  interveneOrder: (no, action, payload) => client.post(`/api/trade/orders/${no}/intervene`, { action, ...payload }),
  listOrderInterventions: (q?: PageQ & { orderNo?: string; action?: string }) => client.get("/api/trade/order-interventions", q),
  listOrderEvents: (orderNo: string) => client.get("/api/trade/order-events", { orderNo }),

  // 订单扩展
  listOrderExceptions: (q?: StatusQ & { type?: string }) => client.get("/api/trade/order-exceptions", q),
  handleOrderException: (no, action, payload) => client.post(`/api/trade/order-exceptions/${no}/handle`, { action, ...payload }),
  listDepositRecords: (q?: StatusQ) => client.get("/api/trade/deposits", q),
  releaseDeposit: (no, reason) => client.post(`/api/trade/deposits/${no}/release`, { reason }),
  buyoutDeposit: (no, payload) => client.post(`/api/trade/deposits/${no}/buyout`, payload),
  dunArrears: (no, payload) => client.post(`/api/trade/deposits/${no}/dun`, payload),

  // 售后处置
  listOrderComplaints: (q?: StatusQ) => client.get("/api/trade/complaints", q),
  createOrderComplaint: (payload) => client.post("/api/trade/complaints", payload),
  handleOrderComplaint: (no, resolution, note) => client.post(`/api/trade/complaints/${no}/handle`, { resolution, note }),
  raiseComplaintWorkOrder: (no) => client.post(`/api/trade/complaints/${no}/work-order`, {}),
  listRefundRecords: (q?: StatusQ) => client.get("/api/trade/refunds", q),
  // 幂等键随 body 走（后端 RefundApplyReq 就带这个字段），重复提交返回已有单而非再退一笔
  createRefund: (payload) => client.post("/api/trade/refunds", payload),
  auditRefund: (no, approve, rejectReason) => client.post(`/api/trade/refunds/${no}/audit`, { approve, rejectReason }),

  // 批次 B4：预约与免费订单归 trade 域
  listReservations: (q?: ReservationQ) => client.get("/api/trade/reservations", q),
  cancelReservation: (no) => client.post(`/api/trade/reservations/${no}/cancel`, {}),
  listFreeOrders: (q?: FreeOrderQ) => client.get("/api/trade/free-orders", q),
  getFreeOrderStats: () => client.get("/api/trade/free-orders/stats"),
};
