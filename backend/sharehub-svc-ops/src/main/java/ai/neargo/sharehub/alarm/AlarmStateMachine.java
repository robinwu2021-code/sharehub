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

    /**
     * event → (from → to)。
     *
     * <p>键与值都是 {@link AlarmStatus} 而非字符串：状态**是**取值域里的一个成员，
     * 写错就编译不过。事件名（{@code ACK}/{@code CLOSE}）仍是字符串 ——
     * 它是动词不是状态，两者曾被混为一谈过（`status IN ('OPEN','ACK')` 那次），
     * 类型不同正好让这种混淆无处发生。
     */
    private static final Map<String, Map<AlarmStatus, AlarmStatus>> TRANSITIONS = Map.of(
            "ACK", Map.of(AlarmStatus.OPEN, AlarmStatus.ACKED),
            // 未受理即可直接关闭（误报/自愈），故 CLOSE 接受两个前态
            "CLOSE", Map.of(AlarmStatus.OPEN, AlarmStatus.CLOSED,
                            AlarmStatus.ACKED, AlarmStatus.CLOSED));

    /**
     * 校验并返回目标状态；非法迁移抛异常（由主控统一映射 409）。
     *
     * <p>出入参仍是 {@code String}：落库的是字符串，边界上转换一次，
     * 不动实体字段类型（那会牵出 MyBatis 类型处理器的连锁改动）。
     */
    public String next(String from, String event) {
        Map<AlarmStatus, AlarmStatus> m = TRANSITIONS.get(event);
        // of() 对垃圾值直接抛 —— v1 的 ACK / RESOLVED 走到这里就是非法值，不做运行期兼容
        AlarmStatus to = m == null ? null : m.get(AlarmStatus.of(from));
        if (to == null) {
            throw new IllegalArgumentException("告警状态非法迁移: " + from + " --" + event + "--> ?");
        }
        return to.name();
    }

    public Set<String> events() {
        return TRANSITIONS.keySet();
    }
}
