package ai.neargo.sharehub.agent.ext;

import ai.neargo.sharehub.common.BizException;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 代理清退（F3）：每一步都要上一步的门禁全过才能推进 —— 资产没收回就结账，结完才发现还有柜子在它手里产生分润；
 * 账还没结清就停账号，它连提现申请都提不了、钱永远挂在那儿。
 */
@Component
public class AgentExitStateMachine {

    private static final Map<String, Map<AgentExitStatus, AgentExitStatus>> TRANSITIONS = Map.of(
            "RECLAIMED", Map.of(AgentExitStatus.RECLAIMING, AgentExitStatus.SETTLING),
            "SETTLED", Map.of(AgentExitStatus.SETTLING, AgentExitStatus.CLOSING),
            "CLOSE", Map.of(AgentExitStatus.CLOSING, AgentExitStatus.CLOSED));

    public String next(String from, String event) {
        Map<AgentExitStatus, AgentExitStatus> m = TRANSITIONS.get(event);
        AgentExitStatus to = m == null ? null : m.get(AgentExitStatus.valueOf(from));
        if (to == null) throw BizException.badRequest("error.state.illegal_transition", from, event);
        return to.name();
    }

    /** 当前状态下推进要走的事件。 */
    public String eventOf(String status) {
        return switch (AgentExitStatus.valueOf(status)) {
            case RECLAIMING -> "RECLAIMED";
            case SETTLING -> "SETTLED";
            case CLOSING -> "CLOSE";
            case CLOSED -> throw BizException.conflict("error.agent_exit.closed");
        };
    }
}
