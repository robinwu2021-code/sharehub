package ai.neargo.sharehub.portal.approval;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.common.Json;
import ai.neargo.sharehub.finance.dto.FinDtos;
import ai.neargo.sharehub.finance.service.LedgerService;
import ai.neargo.sharehub.trade.dto.InterventionDtos;
import ai.neargo.sharehub.trade.service.RentOrderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 资金操作的审批闸（运营方原始需求 SEC-03，V189）。
 *
 * <p>客服补偿 / 免单、手工记账凭证：金额 ≥ 阈值（系统参数，空 = 关，即现行）时<b>不执行</b>，
 * 落一张待审批，返回 {@code pendingApproval}；批准时按原请求重放原动作。审批人不得是申请人。
 * 重放失败（例如等待期间订单已归还结算，免单的前提没了）→ 记 FAILED 并把原因如实返回，不硬改。
 */
@Slf4j
@Service
public class ApprovalGate {

    public static final String KIND_COMPENSATE = "ORDER_COMPENSATE";
    public static final String KIND_WAIVE = "ORDER_WAIVE";
    public static final String KIND_VOUCHER = "LEDGER_VOUCHER";
    static final String PENDING = "PENDING", APPROVED = "APPROVED", REJECTED = "REJECTED", FAILED = "FAILED";
    static final String KEY_COMP = "approval.compensation.threshold";
    static final String KEY_VOUCHER = "approval.voucher.threshold";
    private static final String TENANT = "MAIN";

    private final OpsApprovalRequestMapper mapper;
    private final RentOrderService orders;
    private final LedgerService ledger;
    private final SysParamPort params;
    /** 重放走独立事务：失败时原动作的写入整体回滚，审批单再单独记 FAILED —— 不留半截。 */
    private final org.springframework.transaction.support.TransactionTemplate replayTx;

    public ApprovalGate(OpsApprovalRequestMapper mapper, RentOrderService orders, LedgerService ledger, SysParamPort params,
                        org.springframework.transaction.PlatformTransactionManager txManager) {
        this.replayTx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.replayTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.mapper = mapper;
        this.orders = orders;
        this.ledger = ledger;
        this.params = params;
    }

    /** 待审批回执：前端据此提示「已提交审批」，而不是「已执行」。 */
    public record Pending(String pendingApproval, String kind, BigDecimal amount, BigDecimal threshold) {
    }

    // ── 订单干预 ──

    @Transactional
    public Object intervene(String orderNo, InterventionDtos.InterveneReq req) {
        String kind = req == null ? null : "compensate".equals(req.action()) ? KIND_COMPENSATE : "waive".equals(req.action()) ? KIND_WAIVE : null;
        BigDecimal threshold = threshold(KEY_COMP);
        if (kind == null || threshold == null) return orders.intervene(orderNo, req);
        BigDecimal amount = KIND_COMPENSATE.equals(kind) ? req.amount() : waivedAmount(orderNo);
        if (amount == null || amount.compareTo(threshold) < 0) return orders.intervene(orderNo, req);
        if (req.reason() == null || req.reason().isBlank()) {
            throw BizException.badRequest("error.common.missing_parameter", "reason");   // 先校验再排队，别让审批人审一张必然失败的单
        }
        var o = orders.detail(orderNo);
        OpsApprovalRequest r = queue(kind, orderNo, amount, o.currency(), req.reason(), Json.write(req));
        return new Pending(r.getRequestNo(), kind, amount, threshold);
    }

    /** 免单的「金额」= 这单现在的应收（进行中按试算）。 */
    private BigDecimal waivedAmount(String orderNo) {
        var est = orders.estimateNow(orderNo);
        if (est != null) return est.payable();
        var o = orders.detail(orderNo);
        return BigDecimal.valueOf(o.feeAmount());
    }

    // ── 手工凭证 ──

    @Transactional
    public Object postVoucher(FinDtos.LedgerPostReq req) {
        BigDecimal threshold = threshold(KEY_VOUCHER);
        BigDecimal debit = req == null || req.lines() == null ? BigDecimal.ZERO : req.lines().stream()
                .filter(l -> "DEBIT".equals(l.direction()) && l.amount() != null)
                .map(FinDtos.LedgerLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (threshold == null || debit.compareTo(threshold) < 0) return Map.of("voucherNo", ledger.post(req));
        String currency = req.lines().get(0).currency();
        OpsApprovalRequest r = queue(KIND_VOUCHER, null, debit, currency, req.summary(), Json.write(req));
        return new Pending(r.getRequestNo(), KIND_VOUCHER, debit, threshold);
    }

    // ── 审批 ──

    public PageResult<OpsApprovalRequest> page(Integer page, Integer size, String status, String kind) {
        Page<OpsApprovalRequest> p = mapper.selectPage(new Page<>(page == null ? 1 : Math.max(1, page), size == null ? 20 : Math.min(200, Math.max(1, size))),
                new LambdaQueryWrapper<OpsApprovalRequest>()
                        .eq(status != null && !status.isBlank(), OpsApprovalRequest::getStatus, status)
                        .eq(kind != null && !kind.isBlank(), OpsApprovalRequest::getKind, kind)
                        .orderByDesc(OpsApprovalRequest::getId));
        return new PageResult<>(p.getRecords(), p.getTotal());
    }

    public OpsApprovalRequest get(String requestNo) {
        OpsApprovalRequest r = mapper.selectOne(new LambdaQueryWrapper<OpsApprovalRequest>()
                .eq(OpsApprovalRequest::getRequestNo, requestNo).last("limit 1"));
        if (r == null) throw BizException.notFound(requestNo);
        return r;
    }

    /**
     * 批准并重放。重放抛业务异常 → 原动作整体回滚，审批单记 FAILED + 原因，异常照抛给审批人看。
     * 不加 @Transactional：重放与审批单状态是两个事务，正是为了失败时两者分开收场。
     */
    public OpsApprovalRequest approve(String requestNo) {
        OpsApprovalRequest r = decidable(requestNo);
        try {
            String result = replayTx.execute(st -> {
                // 先抢占：并发批准时只有一人能把 PENDING 改走，另一人在这里冲突退出，原动作不会执行两次
                OpsApprovalRequest claim = new OpsApprovalRequest();
                claim.setId(r.getId());
                claim.setVersion(r.getVersion());
                claim.setStatus(APPROVED);
                if (mapper.updateById(claim) == 0) throw BizException.conflict("error.common.state_changed");
                if (KIND_VOUCHER.equals(r.getKind())) return ledger.post(Json.read(r.getPayload(), FinDtos.LedgerPostReq.class));
                var res = orders.intervene(r.getRefNo(), Json.read(r.getPayload(), InterventionDtos.InterveneReq.class));
                return res.intervention() == null ? null : res.intervention().interventionNo();
            });
            r.setVersion(r.getVersion() == null ? null : r.getVersion() + 1);
            r.setResultRef(result);
            r.setStatus(APPROVED);
            log.info("资金操作审批通过并已执行 requestNo={} kind={} ref={} result={}", r.getRequestNo(), r.getKind(), r.getRefNo(), r.getResultRef());
        } catch (RuntimeException ex) {
            r.setStatus(FAILED);
            r.setDecideNote(cut(ex.getMessage()));
            log.warn("资金操作审批通过但执行失败 requestNo={} kind={} ref={}：{}；前提已变，申请人需按现状重新发起",
                    r.getRequestNo(), r.getKind(), r.getRefNo(), ex.getMessage());
            save(r);
            throw ex;
        }
        save(r);
        return r;
    }

    @Transactional
    public OpsApprovalRequest reject(String requestNo, String note) {
        if (note == null || note.isBlank()) throw BizException.badRequest("error.common.missing_parameter", "note");
        OpsApprovalRequest r = decidable(requestNo);
        r.setStatus(REJECTED);
        r.setDecideNote(cut(note.trim()));
        save(r);
        return r;
    }

    /** 权限按种类分：补偿 / 免单归财务审（order:refund:audit），凭证由另一位记账人审（finance:ledger:create）。 */
    public static String permOf(String kind) {
        return KIND_VOUCHER.equals(kind) ? "finance:ledger:create" : "order:refund:audit";
    }

    private OpsApprovalRequest decidable(String requestNo) {
        OpsApprovalRequest r = get(requestNo);
        if (!PENDING.equals(r.getStatus())) throw BizException.conflict("error.common.state_changed");
        LoginUser u = SecurityUtils.currentUser().orElse(null);
        String me = u == null ? null : u.userNo();
        if (me != null && me.equals(r.getApplicantNo())) throw BizException.conflict("error.approval.self_approve");
        r.setApproverNo(me);
        r.setApproverName(u == null ? null : u.username());
        r.setDecidedAt(LocalDateTime.now());
        return r;
    }

    private void save(OpsApprovalRequest r) {
        if (mapper.updateById(r) == 0) throw BizException.conflict("error.common.state_changed");
    }

    private OpsApprovalRequest queue(String kind, String refNo, BigDecimal amount, String currency, String reason, String payload) {
        LoginUser u = SecurityUtils.currentUser().orElse(null);
        OpsApprovalRequest r = new OpsApprovalRequest();
        r.setTenantId(TENANT);
        r.setRequestNo("AP" + System.currentTimeMillis() + Integer.toString((int) (Math.random() * 1296), 36).toUpperCase());
        r.setKind(kind);
        r.setRefNo(refNo);
        r.setAmount(amount);
        r.setCurrency(currency == null ? "AED" : currency);
        r.setReason(cut(reason));
        r.setPayload(payload);
        r.setStatus(PENDING);
        r.setApplicantNo(u == null ? null : u.userNo());
        r.setApplicantName(u == null ? null : u.username());
        mapper.insert(r);
        log.info("资金操作已转审批 requestNo={} kind={} ref={} amount={} applicant={}", r.getRequestNo(), kind, refNo, amount, r.getApplicantNo());
        return r;
    }

    /** 阈值：空 / 非法 = 关闭审批（现行）。非法值另打 WARN，免得运营以为开了其实没开。 */
    private BigDecimal threshold(String key) {
        String v = params.textOf(key, "");
        if (v == null || v.isBlank()) return null;
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            log.warn("系统参数 {}={} 不是金额，审批按关闭处理；请运营改成数字或留空", key, v);
            return null;
        }
    }

    private static String cut(String s) {
        return s == null ? null : s.length() > 250 ? s.substring(0, 250) : s;
    }
}
