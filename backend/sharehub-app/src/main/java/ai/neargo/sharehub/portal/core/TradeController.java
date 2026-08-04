package ai.neargo.sharehub.portal.core;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.trade.dto.InterventionDtos;
import ai.neargo.sharehub.trade.dto.TradeLegacyDtos.RentOrder;
import ai.neargo.sharehub.trade.service.RentOrderService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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

    public TradeController(RentOrderService orderService) {
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
}
