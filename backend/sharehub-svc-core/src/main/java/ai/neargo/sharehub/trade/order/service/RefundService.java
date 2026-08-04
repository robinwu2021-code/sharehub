package ai.neargo.sharehub.trade.order.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.RefundApplyReq;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.RefundAuditReq;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.RefundRecord;

/**
 * 退款审批单业务（{@code ord_refund}）—— **业务审批链，不是渠道退款**。
 *
 * <p>生命周期：{@link #apply}（生成幂等键）→ {@link #audit}（财务审批）
 * → 通过后才由支付域建 {@code pay_refund} 并回填 {@code payRefundNo}/{@code pspTxnNo}。
 *
 * <p>两条硬规则：
 * <ol>
 *   <li>{@code idempotency_key} UNIQUE —— 同一键只受理一次申请，重复申请返回已有单；</li>
 *   <li>驳回**必须**带 {@code rejectReason}，否则抛 {@link IllegalArgumentException}。</li>
 * </ol>
 */
public interface RefundService {

    PageResult<RefundRecord> page(Integer page, Integer size, String keyword, String status);

    /** 退款申请。幂等键已存在 → 直接返回已有单，不新建（防重复退款）。 */
    RefundRecord apply(RefundApplyReq req);

    /** 审批。审批人/审批时间由服务端回填；驳回缺 rejectReason 抛异常；非 PENDING 抛异常。 */
    RefundRecord audit(String refundNo, RefundAuditReq req);
}
