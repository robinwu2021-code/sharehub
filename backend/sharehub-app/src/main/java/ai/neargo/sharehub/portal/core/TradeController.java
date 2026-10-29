package ai.neargo.sharehub.portal.core;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.trade.dto.InterventionDtos;
import ai.neargo.sharehub.trade.order.dto.OrderDtos;
import ai.neargo.sharehub.trade.order.service.OrderEventLogService;
import ai.neargo.sharehub.trade.dto.TradeLegacyDtos.RentOrder;
import ai.neargo.sharehub.trade.service.RentOrderService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * trade 域运营端订单端点（对齐 docs/api §五 与 ops-web {@code http.ts}）。
 *
 * <p>只剩订单：财务列表（share-rules/ledger/settlements）已迁 {@code FinanceController}、
 * 计费模板已迁 {@code PricingController} —— 原 SeedData 骨架随迁移退役，本类不再触内存种子。
 *
 * <p>干预走 {@link InterventionDtos#RULES} 服务端状态机 + {@code ord_intervention} 留痕，
 * 干预记录列表（审计视图）也在本类。
 */
@RestController
@RequestMapping("/api/trade")
public class TradeController {

    private final RentOrderService orderService;
    private final OrderEventLogService eventLogService;

    private final ai.neargo.sharehub.portal.shared.ExportSupport exports;
    private final ai.neargo.sharehub.portal.approval.ApprovalGate approvals;

    public TradeController(RentOrderService orderService, OrderEventLogService eventLogService,
                           ai.neargo.sharehub.portal.shared.ExportSupport exports,
                           ai.neargo.sharehub.portal.approval.ApprovalGate approvals) {
        this.approvals = approvals;
        this.exports = exports;
        this.eventLogService = eventLogService;
        this.orderService = orderService;
    }

    @GetMapping("/orders")
    @PreAuthorize("@perm.can('order:order:read')")
    public PageResult<RentOrder> orders(@RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String status,
                                        // 收款态 PAID / UNPAID（C-BL-04）：运营催缴筛欠费单
                                        @RequestParam(required = false) String payStatus,
                                        // 第 5 批（RNT-03 / R02 / MON-06）：借出日期（业务日，含两端）、商家、站点、设备
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) String agentNo,
                                        @RequestParam(required = false) String siteNo,
                                        @RequestParam(required = false) String cabinetNo) {
        return orderService.pageAdmin(page, size, filter(keyword, status, payStatus, from, to, agentNo, siteNo, cabinetNo));
    }

    /**
     * 订单导出（RNT-03 / AC-09）：同列表筛选、全量（上限 {@link ai.neargo.sharehub.portal.shared.ExportSupport#MAX_ROWS}），
     * {@code format=xlsx|pdf}。此前前端只能导出当前页。
     */
    @GetMapping("/orders/export")
    @PreAuthorize("@perm.can('order:order:export')")
    public void exportOrders(@RequestParam(required = false) String format,
                             @RequestParam(required = false) String keyword,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) String payStatus,
                             @RequestParam(required = false) String from,
                             @RequestParam(required = false) String to,
                             @RequestParam(required = false) String agentNo,
                             @RequestParam(required = false) String siteNo,
                             @RequestParam(required = false) String cabinetNo,
                             jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        var f = filter(keyword, status, payStatus, from, to, agentNo, siteNo, cabinetNo);
        var all = ai.neargo.sharehub.portal.shared.ExportSupport.fetchAll((p, s) -> orderService.pageAdmin(p, s, f).getList());
        String desc = java.util.stream.Stream.of(keyword, status, payStatus, from == null && to == null ? null : (from == null ? "" : from) + " ~ " + (to == null ? "" : to),
                        agentNo, siteNo, cabinetNo).filter(v -> v != null && !v.isBlank())
                .collect(java.util.stream.Collectors.joining(" · "));
        exports.render(ai.neargo.sharehub.common.export.ExportFormat.of(format), "export.orders", desc, java.util.List.of(
                        "export.col.order_no", "export.col.status", "export.col.merchant", "export.col.site", "export.col.cabinet",
                        "export.col.rent_start", "export.col.rent_end", "export.col.duration_min", "export.col.fee",
                        "export.col.max_charge", "export.col.currency", "export.col.pay_status", "export.col.pay_no", "export.col.pay_channel"),
                all.rows().stream().map(o -> java.util.Arrays.<Object>asList(o.orderNo(), o.status(),
                        o.agentName() != null ? o.agentName() : o.agentNo(), o.siteNo(), o.cabinetNo(), o.rentStartAt(), o.rentEndAt(),
                        o.liveDurationMin() != null ? o.liveDurationMin() : o.durationMin(),
                        o.accruedFee() != null ? o.accruedFee() : java.math.BigDecimal.valueOf(o.feeAmount()),
                        o.maxCharge(), o.currency(), o.payStatus(), o.payNo(), o.payChannel())).toList(),
                all.truncated(), "orders").writeTo(response);
    }

    private static RentOrderService.OrderFilter filter(String keyword, String status, String payStatus, String from, String to,
                                                       String agentNo, String siteNo, String cabinetNo) {
        return new RentOrderService.OrderFilter(keyword, status, payStatus, day(from, "from"), day(to, "to"), agentNo, siteNo, cabinetNo);
    }

    private static java.time.LocalDate day(String v, String name) {
        if (v == null || v.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(v.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw ai.neargo.sharehub.common.BizException.badRequest("error.common.invalid_value", name);
        }
    }

    @GetMapping("/orders/{orderNo}")
    @PreAuthorize("@perm.can('order:order:read')")
    public RentOrder order(@PathVariable String orderNo) {
        return orderService.detail(orderNo);
    }

    @PostMapping("/orders/{orderNo}/intervene")
    @PreAuthorize("@perm.can('order:intervene:execute')")
    public Object intervene(@PathVariable String orderNo, @RequestBody InterventionDtos.InterveneReq body) {
        // 补偿 / 免单 ≥ approval.compensation.threshold 时转审批（SEC-03），返回 {pendingApproval}；否则照旧即时执行
        return approvals.intervene(orderNo, body);
    }

    /** 干预留痕分页（审计视图，append-only 表 {@code ord_intervention}）。 */
    @GetMapping("/order-interventions")
    @PreAuthorize("@perm.can('order:order:read')")
    public PageResult<InterventionDtos.OrderIntervention> interventions(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String action) {
        return orderService.interventions(page, size, orderNo, action);
    }

    /**
     * 订单状态流转留痕（审计视图，append 表 {@code ord_event_log}）。
     *
     * <p><b>与上面的干预留痕是两件事，刻意分开两个端点</b>：这里是「状态怎么走的」，
     * 那里是「人做了什么」。合并会丢信息 —— 干预里有 {@code to == null} 的
     * 「只留痕不改状态」动作（免单/补偿/退款申请改的是钱不是状态），
     * 它根本不产生一条状态流转。
     *
     * <p><b>返回 {@code List} 而不是 {@code PageResult}</b>，与隔壁不同：一张订单的
     * 事件是**有界的**（十几条封顶，不随时间增长），分页只会让前端多写一圈翻页代码
     * 去翻一页。干预之所以分页，是因为它还兼作**跨订单**的审计列表（带 action 筛选）；
     * 订单事件没有这个用法。将来真要跨订单审计视图，那时另加一个分页端点，不现在预支。
     */
    @GetMapping("/order-events")
    @PreAuthorize("@perm.can('order:order:read')")
    public List<OrderDtos.OrderEvent> orderEvents(@RequestParam String orderNo) {
        return eventLogService.timeline(orderNo);
    }
}
