package ai.neargo.sharehub.dev;

import org.springframework.stereotype.Component;

import java.util.Map;

/** 试借还状态机：系统驱动（指令回执、仓位识别、超时），运营端只有「发起」。 */
@Component
public class TrialRentStateMachine {

    private static final Map<String, Map<TrialRentStatus, TrialRentStatus>> TRANSITIONS = Map.of(
            "EJECTED", Map.of(TrialRentStatus.EJECTING, TrialRentStatus.WAIT_RETURN),
            "RETURNED", Map.of(TrialRentStatus.WAIT_RETURN, TrialRentStatus.PASSED),
            "FAIL", Map.of(TrialRentStatus.EJECTING, TrialRentStatus.FAILED, TrialRentStatus.WAIT_RETURN, TrialRentStatus.FAILED),
            "EXPIRE", Map.of(TrialRentStatus.EJECTING, TrialRentStatus.EXPIRED, TrialRentStatus.WAIT_RETURN, TrialRentStatus.EXPIRED));

    public String next(String from, String event) {
        Map<TrialRentStatus, TrialRentStatus> m = TRANSITIONS.get(event);
        TrialRentStatus to = m == null ? null : m.get(TrialRentStatus.of(from));
        if (to == null) throw ai.neargo.sharehub.common.BizException.badRequest("error.state.illegal_transition", from, event);
        return to.name();
    }
}
