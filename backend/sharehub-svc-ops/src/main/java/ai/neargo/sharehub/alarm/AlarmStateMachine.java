package ai.neargo.sharehub.alarm;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 告警状态机（[db-design §3.2]，风格对齐 {@code wo.WoStateMachine}）：
 * {@code OPEN → ACKED → CLOSED}，集中定义合法迁移，非法迁移拒。
 *
 * <p>为什么单独立一个组件而不是在 service 里写 if：状态是这张表最容易被后续需求悄悄放宽的地方
 * （「先临时允许 CLOSED 再 ACK 一下」），集中一处才看得见改动。
 *
 * <p>⚠️ 迁移注意：v1 {@code dev_alert.status} 是 {@code OPEN/ACK/RESOLVED}，
 * v2 是 {@code OPEN/ACKED/CLOSED}，值映射写在 {@code ddl/pb_core-v2-ops-alarm.sql} 注释里。
 * 本状态机只认 v2 值，历史值必须在迁移期换掉，不在运行期兼容。
 */
@Component
public class AlarmStateMachine {

    public static final String OPEN = "OPEN";
    public static final String ACKED = "ACKED";
    public static final String CLOSED = "CLOSED";

    /** event → (from → to)。 */
    private static final Map<String, Map<String, String>> TRANSITIONS = Map.of(
            "ACK", Map.of(OPEN, ACKED),
            // 未受理即可直接关闭（误报/自愈），故 CLOSE 接受两个前态
            "CLOSE", Map.of(OPEN, CLOSED, ACKED, CLOSED));

    /** 校验并返回目标状态；非法迁移抛异常（由主控统一映射 409）。 */
    public String next(String from, String event) {
        Map<String, String> m = TRANSITIONS.get(event);
        String to = m == null ? null : m.get(from);
        if (to == null) {
            throw new IllegalArgumentException("告警状态非法迁移: " + from + " --" + event + "--> ?");
        }
        return to;
    }

    public Set<String> events() {
        return TRANSITIONS.keySet();
    }
}
