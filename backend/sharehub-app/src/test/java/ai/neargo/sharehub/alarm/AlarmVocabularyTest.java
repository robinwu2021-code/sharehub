package ai.neargo.sharehub.alarm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 告警域四个词表与状态机（L1.5 收敛的第一个域）。
 *
 * <p><b>不起 Spring 上下文</b>：状态机是纯逻辑，它的正确性不该依赖一个能连上的数据库。
 * 此前这些断言挤在 {@code scenario.AlarmStatusVocabularyTest}（{@code @SpringBootTest}）里，
 * 结果是开发库一有迁移失败，连「ACK 是不是合法前态」都验不了 ——
 * 把纯逻辑和需要库的行为分开，是为了让前者永远可跑。
 *
 * <p>需要库的那部分（自动开工单要覆盖 OPEN 与 ACKED）仍留在
 * {@code scenario.AlarmStatusVocabularyTest}。
 */
class AlarmVocabularyTest {

    // ───────────────────── 词表本身 ─────────────────────

    @Test
    @DisplayName("四个词表与 DDL 注释逐项一致")
    void vocabulariesMatchDdl() {
        // DDL: dev_alarm.status COMMENT 'OPEN/ACKED/CLOSED'
        assertThat(AlarmStatus.values()).extracting(Enum::name)
                .containsExactly("OPEN", "ACKED", "CLOSED");
        // DDL: dev_alarm.level / dev_alarm_code.level COMMENT 'INFO/WARN/CRITICAL'
        assertThat(AlarmLevel.values()).extracting(Enum::name)
                .containsExactly("INFO", "WARN", "CRITICAL");
        // DDL: dev_alarm_rule.status COMMENT 'ACTIVE/INACTIVE'
        assertThat(AlarmRuleStatus.values()).extracting(Enum::name)
                .containsExactly("ACTIVE", "INACTIVE");
        // DDL: dev_alarm_notice.status COMMENT 'SENT/FAILED'
        assertThat(AlarmNoticeStatus.values()).extracting(Enum::name)
                .containsExactly("SENT", "FAILED");
    }

    @Test
    @DisplayName("of() 对非法值抛错，且报错里列出合法取值")
    void ofRejectsGarbageAndSaysWhatIsLegal() {
        assertThatThrownBy(() -> AlarmStatus.of("ACK"))
                .as("ACK 是事件名，不是状态 —— 正是那次无症状缺陷的源头")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPEN/ACKED/CLOSED");

        assertThatThrownBy(() -> AlarmStatus.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AlarmStatus.of("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AlarmLevel.of("URGENT")).hasMessageContaining("INFO/WARN/CRITICAL");
        assertThatThrownBy(() -> AlarmRuleStatus.of("ENABLED"))
                .as("规则用 ACTIVE/INACTIVE，不是别处那套 ENABLED/DISABLED")
                .hasMessageContaining("ACTIVE/INACTIVE");
    }

    @Test
    @DisplayName("of() 容忍大小写与空白，但不容忍别的词表")
    void ofIsLenientOnFormOnly() {
        assertThat(AlarmStatus.of(" acked ")).isEqualTo(AlarmStatus.ACKED);
        assertThatThrownBy(() -> AlarmStatus.of("RESOLVED"))
                .as("RESOLVED 属 v1 dev_alert 的词表，已随 V45 退役，不在运行期兼容")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ───────────────────── 状态机 ─────────────────────

    private final AlarmStateMachine sm = new AlarmStateMachine();

    @Test
    @DisplayName("合法迁移：OPEN --ACK--> ACKED，OPEN/ACKED --CLOSE--> CLOSED")
    void legalTransitions() {
        assertThat(sm.next("OPEN", "ACK")).isEqualTo("ACKED");
        assertThat(sm.next("OPEN", "CLOSE")).isEqualTo("CLOSED");
        assertThat(sm.next("ACKED", "CLOSE"))
                .as("未受理即可直接关闭（误报/自愈），故 CLOSE 接受两个前态")
                .isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("非法迁移被拒，且状态与事件不会被混为一谈")
    void illegalTransitionsRejected() {
        assertThatThrownBy(() -> sm.next("CLOSED", "ACK"))
                .as("已关闭的不能再受理")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sm.next("ACKED", "ACK"))
                .as("已受理的不能再受理一次")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sm.next("ACK", "CLOSE"))
                .as("'ACK' 是事件名，拿它当前态必须被拒 —— 这正是缺陷的形状")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sm.next("OPEN", "RESOLVE"))
                .as("RESOLVE 不是本状态机的事件")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("事件集合就是 ACK 与 CLOSE 两个")
    void eventsAreClosed() {
        assertThat(sm.events()).containsExactlyInAnyOrder("ACK", "CLOSE");
    }
}
