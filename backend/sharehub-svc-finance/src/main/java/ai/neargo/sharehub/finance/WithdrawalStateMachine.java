package ai.neargo.sharehub.finance;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 提现单状态机。状态 [db-design §5.5]：{@code APPLY} / {@code AUDIT} / {@code PAYING} / {@code PAID} / {@code FAILED}。
 *
 * <p>迁移：
 * <pre>
 *   APPLY --SUBMIT--> AUDIT          （进入审批队列）
 *   APPLY|AUDIT --APPROVE--> PAYING  （审批通过，出款在途）
 *   APPLY|AUDIT --REJECT --> FAILED  （驳回，必须带 reject_reason）
 *   PAYING --PAY--> PAID / --FAIL--> FAILED
 * </pre>
 *
 * <p>{@code PAID} 与 {@code FAILED} 是终态，**不提供任何出边** —— 已打款的提现不能被改回，
 * 打错了走反向入账，不改这张单。
 */
@Component
public class WithdrawalStateMachine {

    private static final Map<String, Map<String, String>> TRANSITIONS = Map.of(
            "SUBMIT", Map.of("APPLY", "AUDIT"),
            "APPROVE", Map.of("APPLY", "PAYING", "AUDIT", "PAYING"),
            "REJECT", Map.of("APPLY", "FAILED", "AUDIT", "FAILED"),
            "PAY", Map.of("PAYING", "PAID"),
            "FAIL", Map.of("PAYING", "FAILED"));

    /** 校验并返回目标状态；非法迁移抛异常。 */
    public String next(String from, String event) {
        Map<String, String> m = TRANSITIONS.get(event);
        String to = m == null ? null : m.get(from);
        if (to == null) {
            throw new IllegalArgumentException("提现单状态非法迁移: " + from + " --" + event + "--> ?");
        }
        return to;
    }
}
