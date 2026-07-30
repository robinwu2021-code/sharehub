package ai.neargo.powerbank.portal.ops;

import ai.neargo.powerbank.common.Kw;
import ai.neargo.common.core.PageResult;
import ai.neargo.powerbank.common.Pages;
import ai.neargo.powerbank.dto.Dto.*;
import ai.neargo.powerbank.seed.SeedData;
import ai.neargo.powerbank.trade.service.RentOrderService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * trade 域运营端端点（对齐 docs/api §五 与 ops-web {@code http.ts}）：
 * 订单（+客服干预，已转 {@link RentOrderService} 落库）/ 分润规则 / 账务分录 / 结算 / 提现（+审核）/ 计费模板（后者仍内存）。
 */
@RestController
@RequestMapping("/api/trade")
public class TradeController {

    private final SeedData db;
    private final RentOrderService orderService;

    public TradeController(SeedData db, RentOrderService orderService) {
        this.db = db;
        this.orderService = orderService;
    }

    // —— 订单（落库，转 RentOrderService）——
    @GetMapping("/orders")
    public PageResult<RentOrder> orders(@RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) String status) {
        return orderService.pageAdmin(page, size, keyword, status);
    }

    @GetMapping("/orders/{orderNo}")
    public RentOrder order(@PathVariable String orderNo) {
        return orderService.detail(orderNo);
    }

    @PostMapping("/orders/{orderNo}/intervene")
    @PreAuthorize("@perm.can('order:intervene:execute')")
    public OkResult intervene(@PathVariable String orderNo, @RequestBody(required = false) Map<String, Object> body) {
        String action = body == null ? null : String.valueOf(body.get("action"));
        return orderService.intervene(orderNo, action);
    }

    // —— 财务 ——
    @GetMapping("/share-rules")
    public PageResult<ShareRule> shareRules(@RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          @RequestParam(required = false) String keyword) {
        List<ShareRule> rows = db.shareRules().stream().filter(s -> Kw.hit(keyword, s.payeeName())).toList();
        return Pages.of(rows, page, size);
    }

    @GetMapping("/ledger")
    public PageResult<LedgerEntry> ledger(@RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) String keyword) {
        List<LedgerEntry> rows = db.ledger().stream()
                .filter(l -> Kw.hit(keyword, l.account(), l.orderNo(), l.voucherNo())).toList();
        return Pages.of(rows, page, size);
    }

    @GetMapping("/settlements")
    public PageResult<Settlement> settlements(@RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) String keyword) {
        List<Settlement> rows = db.settlements().stream()
                .filter(s -> Kw.hit(keyword, s.payeeName(), s.settleNo())).toList();
        return Pages.of(rows, page, size);
    }

    @GetMapping("/withdrawals")
    public PageResult<Withdrawal> withdrawals(@RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) String keyword) {
        List<Withdrawal> rows = db.withdrawals().stream()
                .filter(w -> Kw.hit(keyword, w.payeeName(), w.withdrawNo())).toList();
        return Pages.of(rows, page, size);
    }

    @PostMapping("/withdrawals/{withdrawNo}/audit")
    @PreAuthorize("@perm.can('finance:withdrawal:audit')")
    public OkResult auditWithdrawal(@PathVariable String withdrawNo, @RequestBody Map<String, Object> body) {
        boolean approve = body != null && Boolean.TRUE.equals(body.get("approve"));
        db.replaceFirst(db.withdrawals(), w -> w.withdrawNo().equals(withdrawNo), db.withdrawals().stream()
                .filter(w -> w.withdrawNo().equals(withdrawNo)).findFirst()
                .map(w -> new Withdrawal(w.withdrawNo(), w.payeeName(), w.amount(), w.currency(),
                        approve ? "PAYING" : "FAILED", w.appliedAt()))
                .orElse(null));
        return new OkResult(true);
    }

    // —— 计费模板 ——
    @GetMapping("/price-plans")
    public PageResult<PricePlan> pricePlans(@RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          @RequestParam(required = false) String keyword) {
        List<PricePlan> rows = db.pricePlans().stream().filter(p -> Kw.hit(keyword, p.name(), p.scope())).toList();
        return Pages.of(rows, page, size);
    }
}
