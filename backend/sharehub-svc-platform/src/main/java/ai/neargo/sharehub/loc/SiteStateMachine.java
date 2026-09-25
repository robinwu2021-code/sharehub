package ai.neargo.sharehub.loc;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 站点状态机（对齐 ops-web {@code SITE_TRANSITIONS}）。不再自由跳转 —— 原「门店生命周期」可任选阶段，
 * 同一站点可以既「营业中」又「已流失」。
 *
 * <p>GO_LIVE 由系统触发（首台设备上线），前端无按钮。
 */
@Component
public class SiteStateMachine {

    private static final Map<String, Map<SiteStatus, SiteStatus>> TRANSITIONS = Map.of(
            "GO_LIVE", Map.of(SiteStatus.PREPARING, SiteStatus.ACTIVE),
            "PAUSE", Map.of(SiteStatus.ACTIVE, SiteStatus.PAUSED),
            "RESUME", Map.of(SiteStatus.PAUSED, SiteStatus.ACTIVE),
            "WITHDRAW", Map.of(SiteStatus.ACTIVE, SiteStatus.WITHDRAWING, SiteStatus.PAUSED, SiteStatus.WITHDRAWING),
            "CLOSE", Map.of(SiteStatus.WITHDRAWING, SiteStatus.CLOSED, SiteStatus.PREPARING, SiteStatus.CLOSED));

    public String next(String from, String event) {
        Map<SiteStatus, SiteStatus> m = TRANSITIONS.get(event);
        SiteStatus to = m == null ? null : m.get(SiteStatus.of(from));
        if (to == null) throw ai.neargo.sharehub.common.BizException.badRequest("error.state.illegal_transition", from, event);
        return to.name();
    }

    public Set<String> events() {
        return TRANSITIONS.keySet();
    }
}
