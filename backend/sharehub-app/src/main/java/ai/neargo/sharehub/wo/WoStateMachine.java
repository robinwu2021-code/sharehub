package ai.neargo.powerbank.wo;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 工单状态机（独立组件，设计 §五-2）：集中定义合法迁移，非法迁移拒。
 * 状态：CREATED→DISPATCHED→ACCEPTED→PROCESSING→DONE→AUDITED→CLOSED。
 */
@Component
public class WoStateMachine {

    // event → (fromStatus → toStatus)
    private static final Map<String, Map<String, String>> TRANSITIONS = Map.of(
            "DISPATCH", Map.of("CREATED", "DISPATCHED"),
            "ACCEPT", Map.of("DISPATCHED", "ACCEPTED"),
            "PROCESS", Map.of("ACCEPTED", "PROCESSING"),
            "DONE", Map.of("PROCESSING", "DONE"),
            "AUDIT", Map.of("DONE", "AUDITED"),
            "CLOSE", Map.of("AUDITED", "CLOSED"));

    /** 校验并返回目标状态；非法迁移抛异常。 */
    public String next(String from, String event) {
        Map<String, String> m = TRANSITIONS.get(event);
        String to = m == null ? null : m.get(from);
        if (to == null) {
            throw new IllegalArgumentException("工单状态非法迁移: " + from + " --" + event + "--> ?");
        }
        return to;
    }

    public Set<String> events() {
        return TRANSITIONS.keySet();
    }
}
