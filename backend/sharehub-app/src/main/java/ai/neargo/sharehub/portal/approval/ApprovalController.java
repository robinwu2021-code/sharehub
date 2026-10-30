package ai.neargo.sharehub.portal.approval;

import ai.neargo.common.core.PageResult;
import ai.neargo.common.security.rbac.PermChecker;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资金操作审批（SEC-03，V189）：客服补偿 / 免单、手工凭证超阈值后的待办。
 * 列表任一审批码可看；批准 / 驳回按种类逐个校验（补偿 / 免单 = order:refund:audit，凭证 = finance:ledger:create）。
 */
@RestController
@RequestMapping("/api/trade/approvals")
public class ApprovalController {

    private final ApprovalGate gate;
    private final PermChecker perm;

    public ApprovalController(ApprovalGate gate, PermChecker perm) {
        this.gate = gate;
        this.perm = perm;
    }

    /** 出参：不带 payload 原文（里面是整份请求体，列表不需要）。 */
    public record ApprovalRequest(String requestNo, String kind, String refNo, BigDecimal amount, String currency, String reason,
                               String status, String applicantName, String approverName, LocalDateTime decidedAt,
                               String decideNote, String resultRef, LocalDateTime createdAt) {
        static ApprovalRequest of(OpsApprovalRequest r) {
            return new ApprovalRequest(r.getRequestNo(), r.getKind(), r.getRefNo(), r.getAmount(), r.getCurrency(), r.getReason(),
                    r.getStatus(), r.getApplicantName(), r.getApproverName(), r.getDecidedAt(), r.getDecideNote(),
                    r.getResultRef(), r.getCreatedAt());
        }
    }

    public record RejectReq(String note) {
    }

    @GetMapping
    @PreAuthorize("@perm.can('order:refund:audit') or @perm.can('finance:ledger:create')")
    public PageResult<ApprovalRequest> list(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                         @RequestParam(required = false) String status, @RequestParam(required = false) String kind) {
        var r = gate.page(page, size, status, kind);
        return new PageResult<>(r.getList().stream().map(ApprovalRequest::of).toList(), r.getTotal());
    }

    @PostMapping("/{requestNo}/approve")
    @PreAuthorize("@perm.can('order:refund:audit') or @perm.can('finance:ledger:create')")
    public ApprovalRequest approve(@PathVariable String requestNo) {
        check(requestNo);
        return ApprovalRequest.of(gate.approve(requestNo));
    }

    @PostMapping("/{requestNo}/reject")
    @PreAuthorize("@perm.can('order:refund:audit') or @perm.can('finance:ledger:create')")
    public ApprovalRequest reject(@PathVariable String requestNo, @RequestBody RejectReq body) {
        check(requestNo);
        return ApprovalRequest.of(gate.reject(requestNo, body == null ? null : body.note()));
    }

    private void check(String requestNo) {
        String code = ApprovalGate.permOf(gate.get(requestNo).getKind());
        if (!perm.can(code)) throw new AccessDeniedException(code);
    }
}
