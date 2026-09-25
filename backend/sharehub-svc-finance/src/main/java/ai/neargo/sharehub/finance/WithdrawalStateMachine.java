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

    /**
     * event → (fromStatus → toStatus)。
     *
     * <p><b>状态是 {@link WithdrawalStatus}，事件仍是字符串</b>：事件是动词、状态是名词，
     * 类型不同让两者无法被混为一谈。
     */
    private static final Map<String, Map<WithdrawalStatus, WithdrawalStatus>> TRANSITIONS = Map.of(
            "SUBMIT", Map.of(WithdrawalStatus.APPLY, WithdrawalStatus.AUDIT),
            "APPROVE", Map.of(WithdrawalStatus.APPLY, WithdrawalStatus.PAYING,
                              WithdrawalStatus.AUDIT, WithdrawalStatus.PAYING),
            "REJECT", Map.of(WithdrawalStatus.APPLY, WithdrawalStatus.FAILED,
                             WithdrawalStatus.AUDIT, WithdrawalStatus.FAILED),
            "PAY", Map.of(WithdrawalStatus.PAYING, WithdrawalStatus.PAID),
            "FAIL", Map.of(WithdrawalStatus.PAYING, WithdrawalStatus.FAILED));

    /** 校验并返回目标状态；非法迁移抛异常。 */
    public String next(String from, String event) {
        Map<WithdrawalStatus, WithdrawalStatus> m = TRANSITIONS.get(event);
        WithdrawalStatus to = m == null ? null : m.get(WithdrawalStatus.of(from));
        if (to == null) {
            throw new IllegalArgumentException("提现单状态非法迁移: " + from + " --" + event + "--> ?");
        }
        return to.name();
    }
}
