package ai.neargo.sharehub.trade.order.service;

import ai.neargo.sharehub.trade.order.dto.OrderDtos.OrderEvent;

import java.util.List;

/**
 * 订单状态时间线（{@code ord_event_log}，append 表）。
 *
 * <p>写入方是本子域各状态流转点（异常处置、投诉处理、退款审批、预约取消、押金解冻…），
 * 读出方是订单详情页。**只追加不修改** —— 流水一旦被改，时间线就不再是证据。
 *
 * <p>本服务不挂独立端点：时间线随订单详情 {@code GET /api/trade/orders/{no}} 一并返回
 * （该端点在 {@code portal/ops/TradeController} 已占用，不重复映射）。
 */
public interface OrderEventLogService {

    /** 追加一条流水。{@code operator} 由调用方传当前登录人；{@code createdAt} 服务端打点。 */
    void append(String orderNo, String fromStatus, String toStatus, String event, String operator);

    /** 按订单号取时间线，时间正序。 */
    List<OrderEvent> timeline(String orderNo);
}
