package ai.neargo.sharehub.cs.port;

import ai.neargo.sharehub.api.core.port.RefundApplyPort;
import ai.neargo.sharehub.cs.entity.CsTicket;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 报障转退款的真实现：建一张**待财务审批**的退款申请。
 *
 * <p>在此之前装配的是一个只发号的占位实现：报障单看起来「已转退款 RFD-STUB-…」，
 * 而财务侧没有任何待办 —— <b>钱不会到用户手上</b>（2026-09-28 查实）。
 *
 * <p><b>这一步不动钱</b>：{@code ord_refund} 是业务审批链，财务审批通过之后才由支付域真退。
 * 所以自动转退款是安全的 —— 它把用户的诉求变成财务的一条待办，而不是一次退款。
 *
 * <p>幂等键用报障单号：{@code idempotency_key} 上有 UNIQUE，于是「重复转退款」
 * 返回同一张单，不会退第二笔。
 *
 * <p>返回 {@code null} 表示这条路走不通（没关联订单 / 订单查不到 / 没有可退金额），
 * 调用方据此改走人工 —— 编一个退款号出来，就回到了桩的老路。
 */
@Primary
@Component
public class TradeTicketRefundPort implements TicketRefundPort {

    private final RefundApplyPort refunds;

    public TradeTicketRefundPort(RefundApplyPort refunds) {
        this.refunds = refunds;
    }

    @Override
    public String createRefund(CsTicket ticket) {
        if (ticket.getOrderNo() == null || ticket.getOrderNo().isBlank()) {
            return null;   // 没有订单就无从退款（运营端同一口径：按钮 disabled 并说明原因）
        }
        return refunds.applyForOrder(ticket.getOrderNo(), ticket.getTicketNo(),
                "用户报障 " + ticket.getTicketNo() + "：" + (ticket.getIssue() == null ? "" : ticket.getIssue()));
    }
}
