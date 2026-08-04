package ai.neargo.sharehub.cs.port;

import ai.neargo.sharehub.cs.entity.CsTicket;

/**
 * 报障 → 退款申请的出站口（{@code cs_ticket.refund_no} 出口②）。
 *
 * <p>退款单 {@code ord_refund} 归 trade 域，且带 {@code idempotency_key}
 * （[db-design §1.6]，防重复退款）。cs 域只声明「这个诉求该退款」，
 * 金额认定、审批链、幂等键生成都属 trade 域职责。
 *
 * <p>建单动作对应 [api/README §6A.1] 判据二「需要人做判断才成立的 → 运营端页面建」：
 * 客服认定该退款后才调，不由系统自动触发（除非问题字典明确标了 TO_REFUND）。
 */
public interface TicketRefundPort {

    /**
     * 为报障单发起退款申请（待财务审批，非直接出款）。
     *
     * @return 新建（或已存在）的 {@code refund_no}
     */
    String createRefund(CsTicket ticket);
}
