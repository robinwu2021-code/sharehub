package ai.neargo.sharehub.portal.finance;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.finance.service.AdjustmentService;
import ai.neargo.sharehub.finance.service.AdjustmentService.Adjustment;
import ai.neargo.sharehub.finance.service.AdjustmentService.ConfirmReq;
import ai.neargo.sharehub.finance.service.AdjustmentService.VoidReq;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 结算调整项（运营核心流程 C9）：撤场关闭时系统按合同生成建议值，财务确认（可改金额，改了须说明）后并入下一次出账。
 * 调整项不走提现 —— 它改的是对场地方的应付，而不是一笔独立的钱。
 */
@RestController
@RequestMapping("/api/ops/settlement-adjustments")
public class AdjustmentController {

    private final AdjustmentService adjustments;

    public AdjustmentController(AdjustmentService adjustments) {
        this.adjustments = adjustments;
    }

    @GetMapping
    @PreAuthorize("@perm.can('finance:settlement:read')")
    public PageResult<Adjustment> page(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                       @RequestParam(required = false) String status, @RequestParam(required = false) String payeeNo,
                                       @RequestParam(required = false) String siteNo,
                                       @RequestParam(required = false) String kind,
                                       @RequestParam(required = false) String source,
                                       @RequestParam(required = false) String period) {
        return adjustments.page(page, size, status, payeeNo, siteNo, kind, source, period);
    }

    @PostMapping("/{adjNo}/confirm")
    @PreAuthorize("@perm.can('finance:settlement:confirm')")
    public Adjustment confirm(@PathVariable String adjNo, @RequestBody(required = false) ConfirmReq r) {
        return adjustments.confirm(adjNo, r == null ? null : r.amount(), r == null ? null : r.note());
    }

    @PostMapping("/{adjNo}/void")
    @PreAuthorize("@perm.can('finance:settlement:confirm')")
    public Adjustment voidIt(@PathVariable String adjNo, @RequestBody VoidReq r) {
        return adjustments.voidIt(adjNo, r == null ? null : r.reason());
    }
}
