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

    public TradeController(RentOrderService orderService, OrderEventLogService eventLogService) {
        this.eventLogService = eventLogService;
        this.orderService = orderService;
    }

    @GetMapping("/orders")
    @PreAuthorize("@perm.can('order:order:read')")
    public PageResult<RentOrder> orders(@RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String status) {
        return orderService.pageAdmin(page, size, keyword, status);
    }

    @GetMapping("/orders/{orderNo}")
    @PreAuthorize("@perm.can('order:order:read')")
    public RentOrder order(@PathVariable String orderNo) {
        return orderService.detail(orderNo);
    }

    @PostMapping("/orders/{orderNo}/intervene")
    @PreAuthorize("@perm.can('order:intervene:execute')")
    public InterventionDtos.OrderInterveneResult intervene(@PathVariable String orderNo,
                                                           @RequestBody InterventionDtos.InterveneReq body) {
        return orderService.intervene(orderNo, body);
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
