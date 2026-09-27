package ai.neargo.sharehub.api.core.event;

import ai.neargo.sharehub.common.event.DomainEvent;

import java.math.BigDecimal;

/**
 * 订单欠费 —— 结算时扣款没成功（C-BL-04）。core（交易）发布，user 订阅后给用户发站内信。
 *
 * <p><b>为什么走事件而不是直接调站内信服务</b>：user 域已经依赖 trade（结算站内信回查订单），
 * trade 再直接调 user 的服务就成环了（ArchitectureTest#noCyclesBetweenDomains）。
 * 与 {@link OrderSettledEvent} 同一个模式。
 *
 * <p>带 {@code cUserNo}：消费方据此发信，不必回查订单（那又是一次对 trade 的依赖）。
 *
 * @param orderNo  欠费订单
 * @param cUserNo  欠费用户
 * @param amount   应补金额（订单应付）
 * @param currency 币种
 */
public record OrderArrearsEvent(String orderNo, String cUserNo, BigDecimal amount, String currency)
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
        return "ORDER_ARREARS";
    }
}
