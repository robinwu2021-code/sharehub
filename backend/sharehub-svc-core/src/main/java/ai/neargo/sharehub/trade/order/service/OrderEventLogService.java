package ai.neargo.sharehub.trade.order.service;

import ai.neargo.sharehub.trade.order.dto.OrderDtos.OrderEvent;

import java.util.List;

/**
 * 订单状态时间线（{@code ord_event_log}，append 表）。
 *
 * <p>写入方是本子域各状态流转点（异常处置、投诉处理、退款审批、预约取消、押金解冻…），
 * 读出方是订单详情页。**只追加不修改** —— 流水一旦被改，时间线就不再是证据。
 *
 * <p><b>读端点：{@code GET /api/trade/order-events?orderNo=}</b>（2026-09-25 补）。
 *
 * <p>此处原先写的是「不挂独立端点，时间线随订单详情一并返回」—— 那个方案**两边都没落地**：
 * 详情 VO {@code RentOrder} 里从来没有过这个字段，而 {@link #timeline} 的调用方是 0 个。
 * 结果是四个 service 一直在往 {@code ord_event_log} 写，而**写进去的东西谁也读不到**，
 * 排障时「这单怎么走到 EXCEPTION 的」只能翻日志。
 *
 * <p>改成独立端点，两个理由：
 * <ul>
 *   <li>详情是常开的，时间线是排障才看的。挂进详情等于每次开抽屉都多查一张 append 表；</li>
 *   <li>隔壁干预留痕 {@code GET /api/trade/order-interventions} 就是同一类读端点，
 *       同类东西同一种形状。</li>
 * </ul>
 * 原注释里「该端点已占用，不重复映射」说的是不要在同一路径上重复映射，
 * 这并不妨碍开一个**不同**路径。
 */
public interface OrderEventLogService {

    /** 追加一条流水。{@code operator} 由调用方传当前登录人；{@code createdAt} 服务端打点。 */
    void append(String orderNo, String fromStatus, String toStatus, String event, String operator);

    /** 按订单号取时间线，时间正序。 */
    List<OrderEvent> timeline(String orderNo);
}
