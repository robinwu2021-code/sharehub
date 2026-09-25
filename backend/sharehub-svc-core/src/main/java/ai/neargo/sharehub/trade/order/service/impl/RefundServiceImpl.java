package ai.neargo.sharehub.trade.order.service.impl;

import ai.neargo.common.core.ServerException;
import ai.neargo.common.core.ErrorCode;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.RefundApplyReq;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.RefundAuditReq;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.RefundRecord;
import ai.neargo.sharehub.trade.order.entity.OrdRefund;
import ai.neargo.sharehub.trade.order.mapper.OrdRefundMapper;
import ai.neargo.sharehub.trade.order.service.OrderEventLogService;
import ai.neargo.sharehub.trade.order.service.RefundService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 退款审批单实现 —— 资金动作，三处防线都在这个类里：
 *
 * <ol>
 *   <li><b>幂等键</b>：{@code idempotency_key} UNIQUE。申请前先按键查，命中就返回已有单，
 *       不新建也不报错 —— 「重复提交」的正确语义是「你要的那笔已经在了」。
 *       DB 的 UNIQUE 是并发下的第二道。</li>
 *   <li><b>驳回必填原因</b>：{@code approved=false} 而 {@code rejectReason} 为空直接抛，
 *       资金审批的驳回没有原因就不可复核。</li>
 *   <li><b>审批人服务端回填</b>：{@code auditorName}/{@code auditedAt} 一律取当前登录人与服务端时钟，
 *       前端传的审批人**完全不采信** —— 否则申请人可以自己给自己签字。</li>
 * </ol>
 *
 * <p>本表只管审批链；审批通过后调 {@code PaymentPort} 建 {@code pay_refund}
 * 并回填 {@code payRefundNo}/{@code pspTxnNo}，那一步归支付域（见下方 TODO）。
 */
@Service
public class RefundServiceImpl implements RefundService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";

    private static final int KEY_WIDTH = 6;

    private final OrdRefundMapper mapper;
    private final OrderEventLogService eventLog;

    public RefundServiceImpl(OrdRefundMapper mapper, OrderEventLogService eventLog) {
        this.mapper = mapper;
        this.eventLog = eventLog;
    }

    @Override
    public PageResult<RefundRecord> page(Integer page, Integer size, String keyword, String status) {
        LambdaQueryWrapper<OrdRefund> w = new LambdaQueryWrapper<>();
        if (OrderSupport.has(keyword)) {
            String kw = keyword.trim();
            w.and(q -> q.like(OrdRefund::getRefundNo, kw)
                    .or().like(OrdRefund::getOrderNo, kw)
                    .or().like(OrdRefund::getCUserNo, kw));
        }
        if (OrderSupport.has(status)) w.eq(OrdRefund::getStatus, status);
        w.orderByDesc(OrdRefund::getId); // 审批队列：新单在前

        Page<OrdRefund> r = mapper.selectPage(new Page<>(OrderSupport.page(page), OrderSupport.size(size)), w);
        List<RefundRecord> rows = r.getRecords().stream().map(RefundServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public RefundRecord apply(RefundApplyReq req) {
        if (req == null || !OrderSupport.has(req.orderNo())) {
            throw new IllegalArgumentException("orderNo 必填");
        }
        if (!OrderSupport.has(req.userNo())) {
            throw new IllegalArgumentException("userNo 必填");
        }
        if (req.amount() == null || req.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount 必须大于 0");
        }

        // 幂等键必填，**服务端绝不自补**。原实现缺失时 randomUUID()，那等于把第 1 道防线拆了：
        // 同一次点击重试三下 → 三个不同的 UUID → 三条 ord_refund → 审批队列里三笔同额退款，
        // 全批就是真金白银退三次。而「服务端补的键」在语义上根本不可能重复，
        // 只有调用方知道「这两个请求是同一次点击」，所以键必须由调用方给。
        // ops-web 的 RefundApplyPayload.idempotencyKey 已是必填；后端不依赖调用方守规矩。
        if (!OrderSupport.has(req.idempotencyKey())) {
            throw new IllegalArgumentException("idempotencyKey 必填（资金动作禁止服务端自补幂等键）");
        }
        String idem = req.idempotencyKey().trim();

        OrdRefund existing = selectByIdem(idem);
        if (existing != null) {
            return toVO(existing); // 重复提交 → 返回已有单，不新建（防重复退款）
        }

        OrdRefund e = new OrdRefund();
        e.setRefundNo(OrderSupport.nextNo(mapper, "refund_no", BizKey.REFUND, KEY_WIDTH, OrdRefund::getRefundNo));
        e.setTenantId(OrderSupport.TENANT_MAIN);
        e.setOrderNo(req.orderNo());
        e.setCUserNo(req.userNo());
        e.setAmount(req.amount());
        e.setCurrency(OrderSupport.has(req.currency()) ? req.currency() : "AED");
        e.setReason(req.reason());
        e.setApplicantName(OrderSupport.currentName()); // 申请人快照名由服务端回填
        e.setAppliedAt(OrderSupport.now());
        e.setStatus(STATUS_PENDING);
        e.setIdempotencyKey(idem);
        mapper.insert(e);

        eventLog.append(e.getOrderNo(), null, STATUS_PENDING, "REFUND_APPLY", OrderSupport.currentName());
        return toVO(selectByNo(e.getRefundNo()));
    }

    @Override
    public RefundRecord audit(String refundNo, RefundAuditReq req) {
        OrdRefund e = selectByNo(refundNo);
        if (e == null) throw new IllegalArgumentException("退款单不存在: " + refundNo);
        if (req == null || req.approved() == null) {
            throw new IllegalArgumentException("approved 必填");
        }
        if (!STATUS_PENDING.equals(e.getStatus())) {
            throw ServerException.of(ErrorCode.CONFLICT, "退款单非 PENDING，不可审批: " + refundNo + " → " + e.getStatus());
        }

        boolean approved = req.approved();
        if (!approved && !OrderSupport.has(req.rejectReason())) {
            // 资金审批合规：驳回没有原因就无法复核，这里必须硬拦
            throw new IllegalArgumentException("驳回必须填写 rejectReason");
        }

        e.setStatus(approved ? STATUS_APPROVED : STATUS_REJECTED);
        e.setRejectReason(approved ? null : req.rejectReason().trim());
        e.setAuditorName(OrderSupport.currentName()); // 服务端回填，忽略前端传的审批人
        e.setAuditedAt(OrderSupport.now());
        mapper.updateById(e);

        // TODO(支付域)：approved 时应调 PaymentPort 建 pay_refund（1:1），成功后回填
        //  payRefundNo/pspTxnNo 并把状态推到 EXECUTED，失败推 FAILED。
        //  骨架阶段不接渠道，故停在 APPROVED —— 状态语义正确，不伪造执行结果。
        eventLog.append(e.getOrderNo(), STATUS_PENDING, e.getStatus(),
                approved ? "REFUND_APPROVE" : "REFUND_REJECT", OrderSupport.currentName());
        return toVO(selectByNo(refundNo));
    }

    private OrdRefund selectByNo(String no) {
        if (!OrderSupport.has(no)) return null;
        return mapper.selectOne(new LambdaQueryWrapper<OrdRefund>()
                .eq(OrdRefund::getRefundNo, no).last("limit 1"));
    }

    private OrdRefund selectByIdem(String idem) {
        return mapper.selectOne(new LambdaQueryWrapper<OrdRefund>()
                .eq(OrdRefund::getIdempotencyKey, idem).last("limit 1"));
    }

    /** 字段名全链路统一 {@code pspTxnNo}（2026-09-24 拉齐，见 OrderDtos 头部）。 */
    private static RefundRecord toVO(OrdRefund e) {
        return new RefundRecord(e.getRefundNo(), e.getOrderNo(), e.getCUserNo(), e.getAmount(),
                e.getCurrency(), e.getReason(), e.getApplicantName(), e.getAppliedAt(),
                e.getStatus(), e.getIdempotencyKey(), e.getPspTxnNo(),
                e.getAuditorName(), e.getAuditedAt(), e.getRejectReason());
    }
}
