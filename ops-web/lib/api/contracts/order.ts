// 覆盖范围：租借订单与人工干预、异常订单、押金流水、售后处置（投诉 / 退款审批）、
// 预约订单、免费订单及其统计。
import type { PageQ, OrderQ, StatusQ, ReservationQ, FreeOrderQ } from "../query";
import type {
  PageResult, RentOrder, OrderException, DepositRecord,
  OrderComplaint, ComplaintResolution, RefundRecord,
  Reservation, FreeOrder, FreeOrderStats,
} from "../../types";

export interface OrderApi {
  listOrders(q?: OrderQ): Promise<PageResult<RentOrder>>;
  getOrder(orderNo: string): Promise<RentOrder>;
  interveneOrder(orderNo: string, action: string): Promise<{ ok: true }>;

  // === 订单扩展 tab ===
  listOrderExceptions(q?: PageQ): Promise<PageResult<OrderException>>;
  listDepositRecords(q?: PageQ): Promise<PageResult<DepositRecord>>;

  // === 售后处置（投诉订单 / 退款审批队列）===
  listOrderComplaints(q?: StatusQ): Promise<PageResult<OrderComplaint>>;
  /** 处理投诉：写入处理结果 + 说明，落 RESOLVED/REJECTED。 */
  handleOrderComplaint(complaintNo: string, resolution: ComplaintResolution, note: string): Promise<OrderComplaint>;
  /** 投诉转工单：投诉-订单-工单闭环（竞品此处断链）。 */
  raiseComplaintWorkOrder(complaintNo: string): Promise<OrderComplaint>;
  listRefundRecords(q?: StatusQ): Promise<PageResult<RefundRecord>>;
  /** 退款审批：驳回必须带原因。幂等键由申请侧生成，审批不重发。 */
  auditRefund(refundNo: string, approve: boolean, rejectReason?: string): Promise<RefundRecord>;

  // === 批次 B4：预约订单 / 免费订单（规格 §3 §4）===
  listReservations(q?: ReservationQ): Promise<PageResult<Reservation>>;
  /** 取消预约：仅 PENDING 可取消（后端同样校验，前端按钮先行拦截）。 */
  cancelReservation(reservationNo: string): Promise<Reservation>;
  listFreeOrders(q?: FreeOrderQ): Promise<PageResult<FreeOrder>>;
  /** 免费订单页头统计：本月单数 / 累计减免（成本管控，须为全量口径而非当页）。 */
  getFreeOrderStats(): Promise<FreeOrderStats>;
}
