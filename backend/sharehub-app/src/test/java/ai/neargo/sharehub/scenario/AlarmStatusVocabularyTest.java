package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.alarm.AlarmStateMachine;
import ai.neargo.sharehub.alarm.service.AlarmService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 告警状态词表（L0 对账 §3.2 查出的缺陷）。
 *
 * <p><b>这条测试守的是一个曾经无症状的真缺陷</b>：{@code autoRaiseWorkOrders()} 原本查
 * {@code status IN ('OPEN','ACK')}，但 {@code "ACK"} 是<b>事件名</b>不是状态 ——
 * {@link AlarmStateMachine} 落库的是 {@code ACKED}，DDL 注释写 {@code OPEN/ACKED/CLOSED}，
 * 演示数据写的也是 {@code ACKED}。<b>没有任何一行数据的 status 会等于 {@code "ACK"}</b>，
 * 于是已受理的告警永远开不出工单 —— 不报错、不留日志、少的那部分工单不会自己喊疼。
 *
 * <p>起因是裸字符串：{@code ACK} 与 {@code ACKED} 差一个字母，编译器无从分辨。
 * 修法是让状态值只有一个来源（{@link AlarmStateMachine} 的常量），本测试是它的反向对照。
 *
 * <p><b>为什么三个状态都要断言</b>：只断言 ACKED 会被一个"把条件放开到查全表"的错误修法蒙混过去。
 * CLOSED 那条是边界 —— 已关闭的告警<b>不该</b>再开单。
 */
@SpringBootTest
class AlarmStatusVocabularyTest {

    @Autowired
    AlarmService alarmService;

    @Autowired
    JdbcTemplate jdbc;

    private String prefix;

    @BeforeEach
    void seedThreeStates() {
        prefix = "ALMVOC" + System.nanoTime();
        insert(prefix + "-OPEN", AlarmStateMachine.OPEN);
        insert(prefix + "-ACKED", AlarmStateMachine.ACKED);
        insert(prefix + "-CLOSED", AlarmStateMachine.CLOSED);
    }

    @AfterEach
    void cleanUp() {
        // 物理删 —— @TableLogic 的软删会把 uk_alarm_no 继续占着，下一次跑就撞唯一键
        jdbc.update("DELETE FROM dev_alarm WHERE alarm_no LIKE ?", prefix + "%");
    }

    private void insert(String alarmNo, String status) {
        jdbc.update("""
                INSERT INTO dev_alarm (alarm_no, tenant_id, cabinet_no, alarm_code, level, source, status, wo_no)
                VALUES (?, 'MAIN', 'CAB-VOC', 'E_TEST', 'WARN', 'DEVICE', ?, NULL)
                """, alarmNo, status);
    }

    private String woNoOf(String suffix) {
        return jdbc.queryForObject(
                "SELECT wo_no FROM dev_alarm WHERE alarm_no = ?", String.class, prefix + suffix);
    }

    @Test
    @DisplayName("自动开工单要覆盖 OPEN 与 ACKED，且不碰 CLOSED")
    void autoRaiseCoversOpenAndAcked() {
        // 前置：三条都还没开单 —— 不先证明这一点，下面的断言可能只是在看别人开的单
        assertThat(woNoOf("-OPEN")).isNull();
        assertThat(woNoOf("-ACKED")).isNull();
        assertThat(woNoOf("-CLOSED")).isNull();

        alarmService.autoRaiseWorkOrders();

        assertThat(woNoOf("-OPEN"))
                .as("未受理的告警当然要开单")
                .isNotNull();
        assertThat(woNoOf("-ACKED"))
                .as("已受理≠已解决：查 'ACK' 而非 'ACKED' 时，这一条永远开不出工单，且完全无症状")
                .isNotNull();
        assertThat(woNoOf("-CLOSED"))
                .as("已关闭的告警不该再开单 —— 把条件放开到查全表同样是错的")
                .isNull();
    }

    @Test
    @DisplayName("状态机只认 v2 词表，不接受 v1 的 ACK / RESOLVED")
    void stateMachineRejectsV1Vocabulary() {
        // v1 dev_alert 的词表是 OPEN/ACK/RESOLVED，v2 是 OPEN/ACKED/CLOSED。
        // 状态机类注释写明「只认 v2 值，历史值必须在迁移期换掉，不在运行期兼容」—— 这里把它钉住。
        assertThat(AlarmStateMachine.ACKED).isEqualTo("ACKED");

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new AlarmStateMachine().next("ACK", "CLOSE"))
                .as("'ACK' 是事件名，不是合法前态")
                .isInstanceOf(IllegalArgumentException.class);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new AlarmStateMachine().next("RESOLVED", "CLOSE"))
                .as("'RESOLVED' 是 v1 词表里的值")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
