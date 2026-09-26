package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **人工派单也要写受理人类型**（接入工单 §5.8 第 2 条）。
 *
 * <h3>为什么这条值钱</h3>
 * 自动派单（{@code dispatchAs}）写 {@code assignee_type}/{@code assignee_id}/{@code dispatch_strategy} 三列，
 * 而人工派单（{@code dispatch}）此前只写受理人名字。于是同一张表里，**人工派的单类型恒为空**，
 * 两处读它的逻辑对这些单永远不成立：
 * <ul>
 *   <li>{@code takeover} 判「这是代理承接的单」→ 判不出 ⇒ <b>超时了也接管不了</b>（返回「只有代理的单能接管」）；</li>
 *   <li>{@code recordCost} 判「代理运维的单记代理」→ 判不出 ⇒ 成本记到站点。</li>
 * </ul>
 * 两者都不报错，只是一直落在错的那一边 —— 所以靠人工测很难发现，得专门断言。
 *
 * <p>本条用**可观察的后果**做断言（派给代理的单能进入接管判定），而不是只查一列值：
 * 只断言列有值的话，哪天类型写成别的字面量（"agent" / "AGENT_OPS"）仍然是绿的。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DispatchAssigneeTypeTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private String admin;
    private String agentNo;
    private final List<String> wos = new java.util.ArrayList<>();

    @BeforeAll
    void setUp() {
        admin = login("ADMIN");
        agentNo = jdbc.query("SELECT agent_no FROM agt_agent WHERE status='ENABLED' AND deleted=0 LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null);
    }

    @AfterAll
    void tearDown() {
        for (String w : wos) {
            jdbc.update("DELETE FROM wo_dispatch WHERE wo_no=?", w);
            jdbc.update("DELETE FROM wo_order WHERE wo_no=?", w);
        }
    }

    @Test
    @DisplayName("① 派给代理 → assignee_type=AGENT，且接管判定不再卡在「只有代理的单能接管」")
    void dispatch_to_agent_marks_type_agent() {
        org.junit.jupiter.api.Assumptions.assumeTrue(agentNo != null, "库里没有启用中的代理，跳过");
        String wo = newWorkOrder();

        Resp d = post("/api/ops/work-orders/" + wo + "/dispatch", Map.of("assignee", agentNo), admin);
        assertThat(d.status).as("派单应成功：%s", d.body).isEqualTo(200);

        assertThat(col(wo, "assignee_type")).isEqualTo("AGENT");
        assertThat(col(wo, "assignee_id")).isEqualTo(agentNo);
        assertThat(col(wo, "dispatch_strategy")).isEqualTo("MANUAL");

        /*
         * 可观察后果：接管此时不该再以「只有代理承接的单能接管」为由拒绝。
         * 它仍会被拒 —— 但理由应当换成「SLA 没超时」，那正说明类型这一关已经过了。
         */
        Resp t = post("/api/ops/work-orders/" + wo + "/takeover", Map.of(), admin);
        assertThat(t.status).isEqualTo(409);
        assertThat(t.msg())
                .as("应当卡在 SLA 未超时，而不是卡在受理人类型")
                .doesNotContain("代理");
    }

    @Test
    @DisplayName("② 派给员工 → assignee_type=EMPLOYEE")
    void dispatch_to_employee_marks_type_employee() {
        // ⚠️ 员工是 ACTIVE、代理是 ENABLED —— 两张表词表不同，写错的表现是这条用例被静默跳过
        String emp = jdbc.query("SELECT employee_no FROM iam_employee WHERE status='ACTIVE' AND deleted=0 LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null);
        org.junit.jupiter.api.Assumptions.assumeTrue(emp != null, "库里没有在职员工，跳过");
        String wo = newWorkOrder();

        assertThat(post("/api/ops/work-orders/" + wo + "/dispatch", Map.of("assignee", emp), admin).status).isEqualTo(200);
        assertThat(col(wo, "assignee_type")).isEqualTo("EMPLOYEE");
    }

    @Test
    @DisplayName("③ 传姓名（老契约）→ 仍然派得出去，但类型留空并告警：接管与成本归属会落在错的一边")
    void assignee_by_name_keeps_working_but_type_is_null() {
        String wo = newWorkOrder();
        // 这个端点的历史契约就是接受姓名（既有用例传的正是「Ahmed Field-Eng」），不能改成只收编号
        Resp r = post("/api/ops/work-orders/" + wo + "/dispatch", Map.of("assignee", "Ahmed Field-Eng"), admin);
        assertThat(r.status).as("%s", r.body).isEqualTo(200);
        assertThat(col(wo, "assignee_name")).isEqualTo("Ahmed Field-Eng");
        assertThat(col(wo, "assignee_type")).as("认不出就留空，不猜").isNull();
        assertThat(col(wo, "assignee_id")).as("姓名不该写进编号列（varchar(36)）").isNull();
    }

    @Test
    @DisplayName("③b 超过编号列长（36）的受理人 → 仍是 200，不能炸成 500")
    void over_length_assignee_does_not_blow_up() {
        /*
         * 三列容量不一致：`wo_order.assignee_name` 是 varchar(64)，
         * 而 `wo_order.assignee_id` 与 `wo_dispatch.assignee_id` 都是 varchar(36)。
         * 此前超过 36 字符的受理人会一路写到 wo_dispatch 才炸成
         * 「Data too long for column 'assignee_id'」⇒ **整个派单 500**（本用例最初就是这么红的）。
         */
        String wo = newWorkOrder();
        String longName = "A".repeat(40);
        Resp r = post("/api/ops/work-orders/" + wo + "/dispatch", Map.of("assignee", longName), admin);
        assertThat(r.status).as("%s", r.body).isEqualTo(200);
        assertThat(col(wo, "assignee_name")).isEqualTo(longName);
        String onTimeline = jdbc.query("SELECT assignee_id FROM wo_dispatch WHERE wo_no=? ORDER BY id DESC LIMIT 1",
                (java.sql.ResultSet rs) -> rs.next() ? rs.getString(1) : null, wo);
        assertThat(onTimeline)
                .as("时间轴上截断保留前 36 字符，仍认得出是谁；置空则这一环凭空断了")
                .isEqualTo(longName.substring(0, 36));
    }

    @Test
    @DisplayName("④ 驳回退回待派池 → 受理人三列一起清（只清名字的话按受理人还筛得到）")
    void reject_clears_all_assignee_columns() {
        org.junit.jupiter.api.Assumptions.assumeTrue(agentNo != null, "库里没有启用中的代理，跳过");
        String wo = newWorkOrder();
        assertThat(post("/api/ops/work-orders/" + wo + "/dispatch", Map.of("assignee", agentNo), admin).status).isEqualTo(200);
        assertThat(col(wo, "assignee_type")).isEqualTo("AGENT");

        Resp r = post("/api/ops/work-orders/" + wo + "/reject", Map.of("reason", "不在我的辖区"), admin);
        assertThat(r.status).as("%s", r.body).isEqualTo(200);
        assertThat(col(wo, "assignee_type")).isNull();
        assertThat(col(wo, "assignee_id")).isNull();
        assertThat(col(wo, "dispatch_strategy")).isNull();
    }

    // ───────────────────────── 夹具 ─────────────────────────

    private String newWorkOrder() {
        String wo = "WODT" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        wos.add(wo);
        jdbc.update("INSERT INTO wo_order (wo_no, tenant_id, type, source, status, priority) "
                + "VALUES (?, 'MAIN', 'FAULT', 'MANUAL', 'CREATED', 'P2')", wo);
        return wo;
    }

    private String col(String woNo, String column) {
        return jdbc.query("SELECT " + column + " FROM wo_order WHERE wo_no=?",
                rs -> rs.next() ? rs.getString(1) : null, woNo);
    }
}
