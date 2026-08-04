package ai.neargo.sharehub.portal.finance;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.OkResult;
import ai.neargo.sharehub.finance.dto.FinDtos;
import ai.neargo.sharehub.finance.entity.ShareRule;
import ai.neargo.sharehub.finance.service.InvoiceService;
import ai.neargo.sharehub.finance.service.ReconcileService;
import ai.neargo.sharehub.finance.service.SettlementService;
import ai.neargo.sharehub.finance.service.ShareService;
import ai.neargo.sharehub.finance.service.WithdrawalService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 财务管理域端点（[api/README §4.3–§4.5]）：分润规则/明细/统计 · 结算 · 对账 · 发票 · 提现申请。
 *
 * <p><b>为什么没有类级 {@code @RequestMapping}</b>：本控制器同时承载运营端 {@code /api/trade/**}
 * 与批处理内部端点 {@code /internal/trade/settlements/generate}，两个前缀无法用一个类级前缀表达，
 * 故逐方法写全路径。
 *
 * <p><b>与既有 {@code TradeController} 的分工</b>（重复映射会让 Spring 启动直接失败）：
 * 财务域 GET 列表（settlements/ledger/share-rules）原在 {@code TradeController} 的 SeedData 骨架，
 * 已随骨架退役迁入本类落库；订单端点仍留在那边 —— 本类持有详情、动作端点、
 * {@code share-rules} 的 POST、以及全新的 records/summaries/reconciles/invoices。
 *
 * <p>控制器只做路由 + 鉴权 + 调 service：不写业务、不碰 mapper。
 */
@RestController
public class FinanceController {

    private final ShareService shareService;
    private final ai.neargo.sharehub.finance.service.LedgerService ledgerService;
    private final ai.neargo.sharehub.trade.order.service.DepositService depositService;
    private final SettlementService settlementService;
    private final WithdrawalService withdrawalService;
    private final ReconcileService reconcileService;
    private final InvoiceService invoiceService;

    public FinanceController(ShareService shareService, SettlementService settlementService,
                             WithdrawalService withdrawalService, ReconcileService reconcileService,
                             InvoiceService invoiceService,
                             ai.neargo.sharehub.finance.service.LedgerService ledgerService,
                             ai.neargo.sharehub.trade.order.service.DepositService depositService) {
        this.ledgerService = ledgerService;
        this.depositService = depositService;
        this.shareService = shareService;
        this.settlementService = settlementService;
        this.withdrawalService = withdrawalService;
        this.reconcileService = reconcileService;
        this.invoiceService = invoiceService;
    }

    // ——————————————————————— 分润（菜单叶：分润明细 / 分润统计 / 分润规则）———————————————————————

    @GetMapping("/api/trade/share-records")
    @PreAuthorize("@perm.can('finance:share_record:read')")
    public PageResult<FinDtos.ShareRecord> shareRecords(@RequestParam(required = false) Integer page,
                                                        @RequestParam(required = false) Integer size,
                                                        @RequestParam(required = false) String keyword,
                                                        @RequestParam(required = false) String dimension,
                                                        @RequestParam(required = false) String status) {
        return shareService.pageRecords(page, size, keyword, dimension, status);
    }

    /**
     * 分润统计 —— **读模型，无对应表**：由 {@code share_record} 按 (dimension, payeeNo, period) 聚合。
     *
     * <p>{@code dimension} 是「一张表 + 维度切换器」的那个切换器参数（VENUE / AGENT），不是两个接口。
     * {@code sortKey} 受白名单管控（{@code shareAmount|pendingAmount|gmv|orderCount}），
     * 白名单外的值由 service 抛异常拒绝 → 400。
     */
    @GetMapping("/api/trade/share-summaries")
    @PreAuthorize("@perm.can('finance:share_record:read')")
    public PageResult<FinDtos.ShareSummary> shareSummaries(@RequestParam(required = false) Integer page,
                                                           @RequestParam(required = false) Integer size,
                                                           @RequestParam(required = false) String dimension,
                                                           @RequestParam(required = false) String period,
                                                           @RequestParam(required = false) String sortKey,
                                                           @RequestParam(required = false) String sortDir) {
        return shareService.summaries(page, size, dimension, period, sortKey, sortDir);
    }

    /** 分润规则列表（自 {@code TradeController} SeedData 骨架迁入，走 {@code share_rule} 表）。 */
    @GetMapping("/api/trade/share-rules")
    @PreAuthorize("@perm.can('finance:share_rule:read')")
    public PageResult<FinDtos.ShareRule> shareRules(@RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size,
                                                    @RequestParam(required = false) String keyword) {
        return shareService.pageRules(page, size, keyword);
    }

    @PostMapping("/api/trade/share-rules")
    @PreAuthorize("@perm.can('finance:share_rule:create')")
    public FinDtos.ShareRule createShareRule(@RequestBody ShareRule body) {
        body.setRuleNo(null); // 新建一律服务端取号，忽略 body 里的键
        return shareService.saveRule(body);
    }

    @PostMapping("/api/trade/share-rules/{ruleNo}")
    @PreAuthorize("@perm.can('finance:share_rule:create')")
    public FinDtos.ShareRule updateShareRule(@PathVariable String ruleNo, @RequestBody ShareRule body) {
        body.setRuleNo(ruleNo); // 路径为准，防越权改他人规则
        return shareService.saveRule(body);
    }

    // ——————————————————————— 结算（菜单叶：结算单 / 代理收益结算）———————————————————————

    /** 结算单列表（自 {@code TradeController} SeedData 骨架迁入，走 {@code stl_settlement} 表）。 */
    @GetMapping("/api/trade/settlements")
    @PreAuthorize("@perm.can('finance:settlement:read')")
    public PageResult<FinDtos.Settlement> settlements(@RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) String status) {
        return settlementService.page(page, size, keyword, status);
    }

    @GetMapping("/api/trade/settlements/{settleNo}")
    @PreAuthorize("@perm.can('finance:settlement:read')")
    public FinDtos.SettlementView settlement(@PathVariable String settleNo) {
        return settlementService.detail(settleNo);
    }

    @PostMapping("/api/trade/settlements/{settleNo}/confirm")
    @PreAuthorize("@perm.can('finance:settlement:confirm')")
    public FinDtos.SettlementView confirmSettlement(@PathVariable String settleNo) {
        return settlementService.confirm(settleNo);
    }

    /**
     * 周期出账（[api/README §4.3]）—— **批处理作业调用，非页面入口**，内网受信，
     * 故<b>不挂 {@code @PreAuthorize}</b>（与既有 {@code TradeInternalController} 同一约定）。
     *
     * @return 本次新生成的结算单号；重跑已出账的账期返回空列表（幂等）
     */
    @PostMapping("/internal/trade/settlements/generate")
    public List<String> generateSettlements(@RequestBody(required = false) Map<String, String> body) {
        String period = body == null ? null : body.get("period");
        String payeeType = body == null ? null : body.get("payeeType");
        return settlementService.generate(period, payeeType);
    }

    // ——————————————————————— 提现（菜单叶：提现审核 —— 申请入口在代理端）———————————————————————

    /**
     * 提现申请。**创建者是代理商/场地方本人**（[api/README §六·A]：运营端没有也不该有创建入口），
     * 故权限码是 {@code finance:withdrawal:apply}（AGENT 角色）而非运营侧的 audit。
     *
     * <p>手续费、状态、申请人三者一律服务端定，入参里没有这些字段。
     */
    @PostMapping("/api/trade/withdrawals")
    @PreAuthorize("@perm.can('finance:withdrawal:apply')")
    public FinDtos.Withdrawal applyWithdrawal(@RequestBody FinDtos.WithdrawApplyReq body) {
        return withdrawalService.apply(body);
    }

    /** 提现审核队列（菜单叶：财务管理 › 提现审核）。 */
    @GetMapping("/api/trade/withdrawals")
    @PreAuthorize("@perm.can('finance:withdrawal:read')")
    public PageResult<FinDtos.Withdrawal> withdrawals(@RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size,
                                                     @RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) String status) {
        return withdrawalService.page(page, size, keyword, status);
    }

    /**
     * 提现审批。
     *
     * <p><b>入参里的 {@code auditorName} 被有意忽略</b>：前端契约会传它
     * （{@code auditWithdrawal(no, approve, rejectReason, auditorName)}），但审批人一律由服务端
     * 从当前登录态回填 —— 信前端传的审批人等于让审批留痕可伪造，那四件套就白做了。
     *
     * <p>驳回必须带 {@code rejectReason}，否则 service 抛异常 → 400。
     */
    @PostMapping("/api/trade/withdrawals/{withdrawNo}/audit")
    @PreAuthorize("@perm.can('finance:withdrawal:audit')")
    public FinDtos.Withdrawal auditWithdrawal(@PathVariable String withdrawNo,
                                             @RequestBody WithdrawAuditReq body) {
        boolean approve = body != null && Boolean.TRUE.equals(body.approve());
        String reason = body == null ? null : body.rejectReason();
        return withdrawalService.audit(withdrawNo, approve, reason);
    }

    /** 审批入参。**不含 auditorName** —— 即便前端传了也不会进入本记录（见上方说明）。 */
    public record WithdrawAuditReq(Boolean approve, String rejectReason) {
    }

    // ——————————————————————— 对账（菜单叶：对账）———————————————————————

    @GetMapping("/api/trade/reconciles")
    @PreAuthorize("@perm.can('finance:recon:read')")
    public PageResult<FinDtos.Reconcile> reconciles(@RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String period) {
        return reconcileService.pageTasks(page, size, keyword, status, period);
    }

    @GetMapping("/api/trade/reconciles/{batchNo}/diffs")
    @PreAuthorize("@perm.can('finance:recon:read')")
    public List<FinDtos.ReconDiffRow> reconDiffs(@PathVariable String batchNo) {
        return reconcileService.diffs(batchNo);
    }

    /**
     * 差错平账处置。{@code action}（四选一）+ {@code handleNote} 必填并落批次留痕
     * （此前被静默丢弃 —— http.ts T0-2 注的「语义缩水」已补齐）；
     * {@code diffId} 指定单条，不带则处置该批次全部未处置差错。返回处置后的批次行（契约形状）。
     */
    @PostMapping("/api/trade/reconciles/{batchNo}/resolve")
    @PreAuthorize("@perm.can('finance:recon:resolve')")
    public FinDtos.Reconcile resolveRecon(@PathVariable String batchNo,
                                          @RequestBody(required = false) Map<String, Object> body) {
        Object raw = body == null ? null : body.get("diffId");
        Long diffId = raw == null ? null : Long.valueOf(String.valueOf(raw));
        String action = body == null ? null : (String) body.get("action");
        String note = body == null ? null : (String) body.get("handleNote");
        String operator = body == null ? null : (String) body.get("operatorName");
        return reconcileService.resolve(batchNo, diffId, action, note, operator);
    }

    // ——————————————————————— 发票（菜单叶：发票）———————————————————————

    @GetMapping("/api/trade/invoices")
    @PreAuthorize("@perm.can('finance:invoice:read')")
    public PageResult<FinDtos.Invoice> invoices(@RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String status) {
        return invoiceService.page(page, size, keyword, status);
    }

    @GetMapping("/api/trade/invoices/{invoiceNo}")
    @PreAuthorize("@perm.can('finance:invoice:read')")
    public FinDtos.InvoiceView invoice(@PathVariable String invoiceNo) {
        return invoiceService.detail(invoiceNo);
    }

    @PostMapping("/api/trade/invoices")
    @PreAuthorize("@perm.can('finance:invoice:issue')")
    public FinDtos.InvoiceView createInvoice(@RequestBody FinDtos.InvoiceSaveReq body) {
        return invoiceService.save(withInvoiceNo(body, null)); // 新建一律服务端取号（前缀 INV）
    }

    @PostMapping("/api/trade/invoices/{invoiceNo}")
    @PreAuthorize("@perm.can('finance:invoice:issue')")
    public FinDtos.InvoiceView updateInvoice(@PathVariable String invoiceNo,
                                             @RequestBody FinDtos.InvoiceSaveReq body) {
        return invoiceService.save(withInvoiceNo(body, invoiceNo)); // 路径为准，防越权改他人发票
    }

    /** record 不可变，改键只能重建一个（比让 DTO 变可变类划算）。 */
    private static FinDtos.InvoiceSaveReq withInvoiceNo(FinDtos.InvoiceSaveReq b, String invoiceNo) {
        return new FinDtos.InvoiceSaveReq(invoiceNo, b.payeeType(), b.payeeNo(), b.payeeName(),
                b.amount(), b.vatTrn(), b.currency(), b.status(), b.orderNos());
    }

    // ───────────────── S1 补齐：凭证 / 结算 / 发票 / 对账 / 押金 ─────────────────

    /**
     * 建凭证 = 记一组借贷分录（服务端校验借贷平衡，不平拒收）。
     * append-only：没有改/删凭证的端点，记错走红冲（见 {@code LedgerService} 类注释）。
     */
    @PostMapping("/api/trade/ledger/vouchers")
    @PreAuthorize("@perm.can('finance:ledger:create')")
    public Object createVoucher(@RequestBody FinDtos.LedgerPostReq body) {
        String voucherNo = ledgerService.post(body);
        return java.util.Map.of("voucherNo", voucherNo);
    }

    /**
     * 周期出账（运营端手动触发入口）。与 {@code /internal/trade/settlements/generate}
     * 同一实现：内部口给批处理（无会话），本口给财务页按钮（走 RBAC）。幂等语义一致。
     */
    @PostMapping("/api/trade/settlements/generate")
    @PreAuthorize("@perm.can('finance:settlement:generate')")
    public List<String> generateSettlementsPublic(@RequestBody(required = false) Map<String, String> body) {
        String period = body == null ? null : body.get("period");
        String payeeType = body == null ? null : body.get("payeeType");
        return settlementService.generate(period, payeeType);
    }

    /** 账务分录列表（自 {@code TradeController} SeedData 骨架迁入，走 {@code acct_ledger} 表）。 */
    @GetMapping("/api/trade/ledger")
    @PreAuthorize("@perm.can('finance:ledger:read')")
    public PageResult<FinDtos.LedgerEntry> ledger(@RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String accountNo,
                                                  @RequestParam(required = false) String bizType) {
        return ledgerService.page(page, size, keyword, accountNo, bizType);
    }

    /**
     * 凭证下钻：同一 voucherNo 的全部分录 + 借贷合计与平衡判定。
     *
     * <p><b>刻意只读</b>：手工记账是会计操作，开口子前必须先定「谁能记、能不能改已过账凭证、
     * 如何强制借贷平衡与留痕」—— 一个不校验平衡的「手工记账」比不做更危险。
     */
    @GetMapping("/api/trade/ledger/vouchers/{voucherNo}")
    @PreAuthorize("@perm.can('finance:ledger:read')")
    public Object voucherDetail(@PathVariable String voucherNo) {
        java.util.List<?> entries = ledgerService.byVoucher(voucherNo);
        java.math.BigDecimal debit = java.math.BigDecimal.ZERO;
        java.math.BigDecimal credit = java.math.BigDecimal.ZERO;
        for (Object o : entries) {
            var e = (ai.neargo.sharehub.finance.dto.FinDtos.LedgerEntry) o;
            if ("DEBIT".equals(e.direction())) debit = debit.add(e.amount());
            else credit = credit.add(e.amount());
        }
        // balanced 由服务端算，不让前端自己加 —— 借贷平衡是记账正确性的判据，
        // 两端各算一次就会出现「页面说平了、后端说没平」。
        return java.util.Map.of("voucherNo", voucherNo, "entries", entries,
                "debit", debit, "credit", credit,
                "balanced", debit.compareTo(credit) == 0);
    }


    /** 结算单构成明细：这张单的钱是哪几笔分润凑出来的。 */
    @GetMapping("/api/trade/settlements/{settleNo}/records")
    @PreAuthorize("@perm.can('finance:settlement:read')")
    public Object settlementRecords(@PathVariable String settleNo,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size) {
        return shareService.pageRecords(page, size, settleNo, null, null);
    }

    /** 对账页头统计：任务数 / 差异数 / 已处理 / 待处理。 */
    @GetMapping("/api/trade/reconciles/stats")
    @PreAuthorize("@perm.can('finance:reconcile:read')")
    public Object reconcileStats(@RequestParam(required = false) String period) {
        return reconcileService.stats(period);
    }

    /** 开具发票。开票人取登录态，不接受入参。幂等：重复开具直接返回。 */
    @PostMapping("/api/trade/invoices/{invoiceNo}/issue")
    @PreAuthorize("@perm.can('finance:invoice:update')")
    public Object issueInvoice(@PathVariable String invoiceNo) {
        return invoiceService.issue(invoiceNo);
    }

    /** 作废发票。**必须填原因** —— 没有原因的作废，稽查时无法解释。 */
    @PostMapping("/api/trade/invoices/{invoiceNo}/void")
    @PreAuthorize("@perm.can('finance:invoice:update')")
    public Object voidInvoice(@PathVariable String invoiceNo,
                              @RequestBody java.util.Map<String, Object> body) {
        Object r = body == null ? null : body.get("voidReason");
        return invoiceService.voidInvoice(invoiceNo, r == null ? null : String.valueOf(r));
    }

    /** 押金转买断：用户不还了，押金抵购机款，充电宝转 SOLD。**与丢失(LOST)财务方向相反**。 */
    @PostMapping("/api/trade/deposits/{depositNo}/buyout")
    @PreAuthorize("@perm.can('order:deposit:update')")
    public Object buyoutDeposit(@PathVariable String depositNo,
                                @RequestBody(required = false) java.util.Map<String, Object> body) {
        Object n = body == null ? null : body.get("note");
        return depositService.buyout(depositNo, n == null ? null : String.valueOf(n));
    }

    /** 欠款催缴：**只留痕不改状态** —— 催缴不改变欠款事实，改状态会让「已催缴」被误读成「已解决」。 */
    @PostMapping("/api/trade/deposits/{depositNo}/dun")
    @PreAuthorize("@perm.can('order:deposit:update')")
    public Object dunDeposit(@PathVariable String depositNo,
                             @RequestBody java.util.Map<String, Object> body) {
        Object c = body == null ? null : body.get("channel");
        Object n = body == null ? null : body.get("note");
        return depositService.dun(depositNo, c == null ? null : String.valueOf(c),
                n == null ? null : String.valueOf(n));
    }
}
