package ai.neargo.sharehub.loc;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 合同状态机 —— 全站唯一的合同迁移定义（对齐 ops-web {@code CONTRACT_TRANSITIONS}）。
 *
 * <p>「签署归档」不是迁移：它在 SIGNED 上补签署件与日期，满足条件时<b>顺带</b>触发 ACTIVATE。
 * ACTIVATE / EXPIRE 由系统触发（定时任务、签署动作），前端无按钮 —— 登记在 known-missing-ui-transitions。
 */
@Component
public class ContractStateMachine {

    private static final Map<String, Map<ContractStatus, ContractStatus>> TRANSITIONS = Map.of(
            "SUBMIT", Map.of(ContractStatus.DRAFT, ContractStatus.PENDING),
            "WITHDRAW", Map.of(ContractStatus.PENDING, ContractStatus.DRAFT),
            "APPROVE", Map.of(ContractStatus.PENDING, ContractStatus.SIGNED),
            "REJECT", Map.of(ContractStatus.PENDING, ContractStatus.DRAFT),
            // 条件加签财务（V104）：运营通过后仍是 PENDING（audit_stage=FINANCE），财务会签才到 SIGNED
            "COSIGN", Map.of(ContractStatus.PENDING, ContractStatus.SIGNED),
            "COSIGN_REJECT", Map.of(ContractStatus.PENDING, ContractStatus.DRAFT),
            "ACTIVATE", Map.of(ContractStatus.SIGNED, ContractStatus.ACTIVE),
            "EXPIRE", Map.of(ContractStatus.ACTIVE, ContractStatus.EXPIRED),
            "TERMINATE", Map.of(ContractStatus.ACTIVE, ContractStatus.TERMINATED));

    public String next(String from, String event) {
        Map<ContractStatus, ContractStatus> m = TRANSITIONS.get(event);
        ContractStatus to = m == null ? null : m.get(ContractStatus.of(from));
        if (to == null) throw ai.neargo.sharehub.common.BizException.badRequest("error.state.illegal_transition", from, event);
        return to.name();
    }

    public Set<String> events() {
        return TRANSITIONS.keySet();
    }
}
