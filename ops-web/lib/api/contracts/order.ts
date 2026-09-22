// 覆盖范围：租借订单与人工干预、异常订单、押金流水、售后处置（投诉 / 退款审批）、
// 预约订单、免费订单及其统计。
import type { PageQ, OrderQ, StatusQ, ReservationQ, FreeOrderQ } from "../query";
import type {
  PageResult, RentOrder, OrderException, DepositRecord,
  OrderComplaint, ComplaintResolution, ComplaintCreatePayload,
  RefundRecord, RefundApplyPayload,
  OrderIntervention, OrderInterventionAction, OrderIntervenePayload, OrderInterveneResult,
  ExceptionHandleAction, OrderExceptionHandlePayload,
  DepositBuyoutPayload, ArrearsDunPayload,
  Reservation, FreeOrder, FreeOrderStats,
} from "../../types";

export interface OrderApi {
  listOrders(q?: OrderQ): Promise<PageResult<RentOrder>>;
  getOrder(orderNo: string): Promise<RentOrder>;
  /**
   * 人工干预（order:intervene:execute）：远程弹出 / 强制归还 / 免单 / 补偿 / 申请退款。
   * **原因必填**（沿用退款审批口径），合法性按 ORDER_INTERVENTIONS 状态机校验，
   * 返回落库后的订单 + 刚写入的干预记录。
   */
  interveneOrder(orderNo: string, action: OrderInterventionAction, payload: OrderIntervenePayload): Promise<OrderInterveneResult>;
  /** 干预记录（审计）：订单详情抽屉的时间线按 `orderNo` 精确过滤。 */
  listOrderInterventions(q?: PageQ & { orderNo?: string; action?: string }): Promise<PageResult<OrderIntervention>>;

  // === 订单扩展 tab ===
  listOrderExceptions(q?: StatusQ & { type?: string }): Promise<PageResult<OrderException>>;
  /**
   * 异常单处置（order:exception:handle）：转工单 / 发起退款 / 直接关闭，三选一。
   * **处置结论必填**，合法性按 EXCEPTION_HANDLINGS 状态机校验（HANDLED 是终态）；
   * 转工单会落一条真实工单、发起退款会落一条 PENDING 退款申请，号回填到异常单上。
   */
  handleOrderException(orderNo: string, action: ExceptionHandleAction, payload: OrderExceptionHandlePayload): Promise<OrderException>;
  listDepositRecords(q?: StatusQ): Promise<PageResult<DepositRecord>>;
  /** 押金解冻（order:order:update）：HELD → RELEASED，原因必填。 */
  releaseDeposit(depositNo: string, reason: string): Promise<DepositRecord>;
  /** 押金买断（order:order:update）：HELD → BOUGHT_OUT，写买断金额（不得超过押金额）。 */
  buyoutDeposit(depositNo: string, payload: DepositBuyoutPayload): Promise<DepositRecord>;
  /** 欠费催缴（order:order:update）：ARREARS 记一次催缴（次数 +1、最后催缴时间），状态不变。 */
  dunArrears(depositNo: string, payload: ArrearsDunPayload): Promise<DepositRecord>;

  // === 售后处置（投诉订单 / 退款审批队列）===
  listOrderComplaints(q?: StatusQ): Promise<PageResult<OrderComplaint>>;
  /**
   * 客服代客登记投诉（order:exception:handle，与异常单处置同一个码——后端亦如此）。
   * 电话/线下渠道的投诉原先只能等 C 端自助提交，队列里根本进不来。
   * 号/提交时间/状态由服务端决定，新登记一律 PENDING。
   */
  createOrderComplaint(payload: ComplaintCreatePayload): Promise<OrderComplaint>;
  /** 处理投诉：写入处理结果 + 说明，落 RESOLVED/REJECTED。 */
  handleOrderComplaint(complaintNo: string, resolution: ComplaintResolution, note: string): Promise<OrderComplaint>;
  /** 投诉转工单：投诉-订单-工单闭环（竞品此处断链）。 */
  raiseComplaintWorkOrder(complaintNo: string): Promise<OrderComplaint>;
  listRefundRecords(q?: StatusQ): Promise<PageResult<RefundRecord>>;
  /**
   * 新建退款申请（order:refund:apply）：落 PENDING 进本队列，审批通过才出款。
   * **幂等键必填**，同键重复提交返回已有单——没有键的重复提交等于真退两笔钱。
   */
  createRefund(payload: RefundApplyPayload): Promise<RefundRecord>;
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
