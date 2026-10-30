package ai.neargo.sharehub.api.core.event;

import ai.neargo.sharehub.common.event.DomainEvent;

import java.math.BigDecimal;

/**
 * 退款已批准 —— core（交易）发布，finance 订阅后按比例冲回该单的分成（V170）。
 *
 * <p><b>为什么在批准时而不是渠道退款成功时</b>：一期退款不接渠道，状态停在 APPROVED
 * （见 {@code RefundServiceImpl#audit} 的 TODO），营收首页的「退款」也按 APPROVED 计。
 * 分成冲回与营收口径用同一个时点，否则商家看到「退了 10 块」而分成一分没少。
 * 接渠道之后若退款失败，由渠道侧再发一次反向事件补回，不在这里预判。
 *
 * @param orderNo  原订单
 * @param refundNo 退款单；消费方据它做幂等（同一笔退款重投只冲一次）
 * @param amount   本次退款金额（正数）
 * @param currency 币种
 */
public record OrderRefundedEvent(String orderNo, String refundNo, BigDecimal amount, String currency)
        implements DomainEvent {

    @Override
    public String aggregateType() {
        return "OrdOrder";
    }

    @Override
    public String aggregateId() {
        return orderNo;
    }

    @Override
    public String eventType() {
        return "ORDER_REFUNDED";
    }
}
