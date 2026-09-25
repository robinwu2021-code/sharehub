package ai.neargo.sharehub.trade;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 租借订单状态机（独立组件，对齐 wo 域 {@code WoStateMachine} 风格）：集中定义合法迁移，非法迁移拒。
 * 状态：CREATED→IN_USE→RETURNED→SETTLED→CLOSED；异常 EXCEPTION。
 *
 * <p>借出创建即置 IN_USE（免押预授权 + 弹仓为骨架）；归还 IN_USE→RETURNED；结算 RETURNED→SETTLED；
 * 客服干预可从任意进行中态强制 CLOSED。
 */
@Component
public class OrdStateMachine {

    /**
     * event → (fromStatus → toStatus)。
     *
     * <p><b>状态是 {@link OrderStatus}，事件仍是字符串</b>：事件是动词、状态是名词。
     * 注意本机只有三条边，而 {@code OrderStatus} 有七个值 —— {@code CREATED} 是建单初值、
     * {@code DISPENSING} 由弹仓回调推进、{@code EXCEPTION} 由异常处置写入，都不走这里。
     * 枚举是**取值域**，状态机是**迁移图**，两者本就不必相等。
     */
    private static final Map<String, Map<OrderStatus, OrderStatus>> TRANSITIONS = Map.of(
            "RETURN", Map.of(OrderStatus.IN_USE, OrderStatus.RETURNED),
            "SETTLE", Map.of(OrderStatus.RETURNED, OrderStatus.SETTLED),
            "CLOSE", Map.of(OrderStatus.SETTLED, OrderStatus.CLOSED),
            // 2026-09-25 业务告警自愈：出宝失败（一直停在出宝中）的订单撤销，不收费（RENT_NOT_DELIVERED）
            "CANCEL", Map.of(OrderStatus.DISPENSING, OrderStatus.CLOSED));

    /** 校验并返回目标状态；非法迁移抛异常。 */
    public String next(String from, String event) {
        Map<OrderStatus, OrderStatus> m = TRANSITIONS.get(event);
        OrderStatus to = m == null ? null : m.get(OrderStatus.of(from));
        if (to == null) {
            throw new IllegalArgumentException("订单状态非法迁移: " + from + " --" + event + "--> ?");
        }
        return to.name();
    }
}
