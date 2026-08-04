package ai.neargo.sharehub.finance;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 结算单状态机（对齐 {@code OrdStateMachine}/{@code WoStateMachine} 风格）：集中定义合法迁移，非法迁移拒。
 *
 * <p>状态 [db-design §5.5]：{@code GEN}（批处理出账）→ {@code CONFIRMED}（运营确认）→ {@code PAID}（打款完成）。
 * <b>单向不可回退</b>：结算单一旦确认就是对场地方/代理商的承诺，撤销只能靠下一期反向出账。
 */
@Component
public class SettlementStateMachine {

    // event → (fromStatus → toStatus)
    private static final Map<String, Map<String, String>> TRANSITIONS = Map.of(
            "CONFIRM", Map.of("GEN", "CONFIRMED"),
            "PAY", Map.of("CONFIRMED", "PAID"));

    /** 校验并返回目标状态；非法迁移抛异常（由 {@code GlobalExceptionHandler} 落 400）。 */
    public String next(String from, String event) {
        Map<String, String> m = TRANSITIONS.get(event);
        String to = m == null ? null : m.get(from);
        if (to == null) {
            throw new IllegalArgumentException("结算单状态非法迁移: " + from + " --" + event + "--> ?");
        }
        return to;
    }
}
