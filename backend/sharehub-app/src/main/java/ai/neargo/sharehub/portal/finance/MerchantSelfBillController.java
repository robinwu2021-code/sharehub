package ai.neargo.sharehub.portal.finance;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.finance.service.SelfBillService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 商家门户「我的自开票」（C10 后续，2026-10-29）：平台代本商家开出的分成税务发票，列表与 PDF。
 *
 * <p>商家号<b>只取会话</b>，不认任何入参 —— 与 {@code /api/agent/me/profile} 同一个口径；表本身也按 agent_no 注册了数据范围。
 * 码用 {@code finance:withdrawal:read}：自开票一张对应一笔到账的提现，看得到提现记录的人就该看得到它的票。
 */
@RestController
@RequestMapping("/api/agent/me/self-bills")
public class MerchantSelfBillController {

    private final SelfBillService selfBills;
    private final TaxInvoiceDocuments docs;

    public MerchantSelfBillController(SelfBillService selfBills, TaxInvoiceDocuments docs) {
        this.selfBills = selfBills;
        this.docs = docs;
    }

    @GetMapping
    @PreAuthorize("@perm.can('finance:withdrawal:read')")
    public PageResult<SelfBillView> mine(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        var r = selfBills.page(page, size, myAgentNo(), null);
        return new PageResult<>(r.getList().stream().map(SelfBillView::of).toList(), r.getTotal());
    }

    @GetMapping("/{billNo}/pdf")
    @PreAuthorize("@perm.can('finance:withdrawal:read')")
    public void pdf(@PathVariable String billNo, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        var b = selfBills.get(billNo);
        // 别家的单按「不存在」回，不告诉调用方这个号存在
        if (!myAgentNo().equals(b.getAgentNo())) throw BizException.notFound(billNo);
        docs.selfBillPdf(b).writeTo(response);
    }

    /** 不是商家会话（运营员工）→ 403：运营看全量走 /api/trade/self-bills。 */
    private static String myAgentNo() {
        LoginUser u = SecurityUtils.currentUser().orElse(null);
        if (u == null || u.agentNo() == null || u.agentNo().isBlank()) throw new AccessDeniedException("merchant session required");
        return u.agentNo();
    }
}
