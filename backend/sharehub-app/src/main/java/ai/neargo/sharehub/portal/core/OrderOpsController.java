package ai.neargo.sharehub.portal.core;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.OkResult;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.ComplaintCreateReq;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.ComplaintHandleReq;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.DepositRecord;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.ExceptionHandleReq;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.FreeOrder;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.FreeOrderStats;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.OrderComplaint;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.OrderException;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.RefundApplyReq;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.RefundAuditReq;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.RefundRecord;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.Reservation;
import ai.neargo.sharehub.trade.order.service.ComplaintService;
import ai.neargo.sharehub.trade.order.service.DepositService;
import ai.neargo.sharehub.trade.order.service.FreeOrderService;
import ai.neargo.sharehub.trade.order.service.OrderExceptionService;
import ai.neargo.sharehub.trade.order.service.RefundService;
import ai.neargo.sharehub.trade.order.service.ReservationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单管理 · 售后与衍生单（[api/README §4.1]）：异常订单 / 投诉订单 / 退款记录 /
 * 预约订单 / 押金与欠费 / 免费订单，共 6 个菜单叶。
 *
 * <p>与 {@link TradeController} 同为 {@code /api/trade} 前缀但**子路径不重叠**：
 * 那边是 {@code orders}/{@code share-rules}/{@code ledger}/{@code settlements}/
 * {@code withdrawals}/{@code price-plans}，本类是上述 6 组。拆开是因为这 6 张表各有独立的
 * 状态机与审批规则，与租借主单不同源。新增映射前务必核对已占用路径 —— 重复映射会让 Spring 启动直接失败。
 *
 * <p>控制器只做三件事：路由、鉴权、调 service。**不写业务、不碰 mapper**
 * （[骨架规约 §4]）。响应包由全局 wrapper 自动加，这里直接返 VO。
 *
 * <p>{@code POST /refunds} 建的是 {@code ord_refund}（**业务审批单**，幂等键在此刻生成），
 * 不是渠道退款 {@code pay_refund} —— 后者要等审批通过才由支付域建，1:1 关联。
 */
@RestController
@RequestMapping("/api/trade")
public class OrderOpsController {

    private final OrderExceptionService exceptionService;
    private final ComplaintService complaintService;
    private final RefundService refundService;
    private final ReservationService reservationService;
    private final DepositService depositService;
    private final FreeOrderService freeOrderService;

    public OrderOpsController(OrderExceptionService exceptionService,
                              ComplaintService complaintService,
                              RefundService refundService,
                              ReservationService reservationService,
                              DepositService depositService,
                              FreeOrderService freeOrderService) {
        this.exceptionService = exceptionService;
        this.complaintService = complaintService;
        this.refundService = refundService;
        this.reservationService = reservationService;
        this.depositService = depositService;
        this.freeOrderService = freeOrderService;
    }

    // —— 异常订单（菜单叶：订单管理 › 异常订单）——

    @GetMapping("/order-exceptions")
    @PreAuthorize("@perm.can('order:exception:read')")
    public PageResult<OrderException> orderExceptions(@RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(required = false) String type) {
        return exceptionService.page(page, size, keyword, status, type);
    }

    @PostMapping("/order-exceptions/{no}/handle")
    @PreAuthorize("@perm.can('order:exception:handle')")
    public OkResult handleOrderException(@PathVariable String no,
                                         @RequestBody(required = false) ExceptionHandleReq body) {
        return exceptionService.handle(no, body);
    }

    // —— 投诉订单（菜单叶：订单管理 › 投诉订单）——

    @GetMapping("/complaints")
    @PreAuthorize("@perm.can('order:exception:read')")
    public PageResult<OrderComplaint> complaints(@RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String issueType) {
        return complaintService.page(page, size, keyword, status, issueType);
    }

    /** 客服代客登记（电话/线下投诉）；C 端提交走 {@code POST /mp/user/report} 自动派生。 */
    @PostMapping("/complaints")
    @PreAuthorize("@perm.can('order:exception:handle')")
    public OrderComplaint createComplaint(@RequestBody ComplaintCreateReq body) {
        return complaintService.create(body);
    }

    @PostMapping("/complaints/{no}/handle")
    @PreAuthorize("@perm.can('order:exception:handle')")
    public OrderComplaint handleComplaint(@PathVariable String no, @RequestBody ComplaintHandleReq body) {
        return complaintService.handle(no, body);
    }

    /** 转工单（**幂等**）：已转过则返回已有 {@code workOrderNo}，不重复开单。权限归工单域。 */
    @PostMapping("/complaints/{no}/work-order")
    @PreAuthorize("@perm.can('workorder:wo:create')")
    public OrderComplaint complaintToWorkOrder(@PathVariable String no) {
        return complaintService.toWorkOrder(no);
    }

    // —— 退款记录（菜单叶：订单管理 › 退款记录）——

    @GetMapping("/refunds")
    @PreAuthorize("@perm.can('order:refund:audit')")
    public PageResult<RefundRecord> refunds(@RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String status) {
        return refundService.page(page, size, keyword, status);
    }

    /** 退款申请。幂等键随 body 传（[api/README §4.1] 的 {@code Idempotency-Key}），重复提交返回已有单。 */
    @PostMapping("/refunds")
    @PreAuthorize("@perm.can('order:refund:apply')")
    public RefundRecord applyRefund(@RequestBody RefundApplyReq body) {
        return refundService.apply(body);
    }

    /**
     * 退款审批。驳回必填 {@code rejectReason}；审批人/审批时间由服务端回填，
     * body 里**没有**审批人字段——前端说自己是谁不作数。
     */
    @PostMapping("/refunds/{refundNo}/audit")
    @PreAuthorize("@perm.can('order:refund:audit')")
    public RefundRecord auditRefund(@PathVariable String refundNo, @RequestBody RefundAuditReq body) {
        return refundService.audit(refundNo, body);
    }

    // —— 预约订单（菜单叶：订单管理 › 预约订单）——

    @GetMapping("/reservations")
    @PreAuthorize("@perm.can('order:order:read')")
    public PageResult<Reservation> reservations(@RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String type) {
        return reservationService.page(page, size, keyword, status, type);
    }

    /** 取消预约：**仅 PENDING 可取消，服务端复校**。 */
    @PostMapping("/reservations/{no}/cancel")
    @PreAuthorize("@perm.can('order:order:update')")
    public OkResult cancelReservation(@PathVariable String no) {
        return reservationService.cancel(no);
    }

    // —— 押金与欠费（菜单叶：订单管理 › 押金与欠费）——

    @GetMapping("/deposits")
    @PreAuthorize("@perm.can('order:order:read')")
    public PageResult<DepositRecord> deposits(@RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String status) {
        return depositService.page(page, size, keyword, status);
    }

    @PostMapping("/deposits/{no}/release")
    // 解冻是**放弃用户押金**，与买断同级，归财务（真源表 §4「押金 解冻/买断」同一个码）。
    // 此前用 order:intervene:execute —— 那是客服持有的码，于是**客服能解冻押金**。
    @PreAuthorize("@perm.can('order:deposit:manage')")
    public OkResult releaseDeposit(@PathVariable String no) {
        return depositService.release(no);
    }

    // —— 免费订单（菜单叶：订单管理 › 免费订单）——
    // 没有独立表：查的是 ord_order WHERE free_reason IS NOT NULL（[db-design §5.1]）。

    @GetMapping("/free-orders")
    @PreAuthorize("@perm.can('order:order:read')")
    public PageResult<FreeOrder> freeOrders(@RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String whitelistReason) {
        return freeOrderService.page(page, size, keyword, whitelistReason);
    }

    /** 页头统计：**全量口径**（本月单量/累计减免额），不是当前页合计。 */
    @GetMapping("/free-orders/stats")
    @PreAuthorize("@perm.can('order:order:read')")
    public FreeOrderStats freeOrderStats() {
        return freeOrderService.stats();
    }
}
