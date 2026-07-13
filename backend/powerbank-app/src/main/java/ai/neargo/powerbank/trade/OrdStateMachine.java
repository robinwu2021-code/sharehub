package ai.neargo.powerbank.trade;

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

    // event → (fromStatus → toStatus)
    private static final Map<String, Map<String, String>> TRANSITIONS = Map.of(
            "RETURN", Map.of("IN_USE", "RETURNED"),
            "SETTLE", Map.of("RETURNED", "SETTLED"),
            "CLOSE", Map.of("SETTLED", "CLOSED"));

    /** 校验并返回目标状态；非法迁移抛异常。 */
    public String next(String from, String event) {
        Map<String, String> m = TRANSITIONS.get(event);
        String to = m == null ? null : m.get(from);
        if (to == null) {
            throw new IllegalArgumentException("订单状态非法迁移: " + from + " --" + event + "--> ?");
        }
        return to;
    }
}
