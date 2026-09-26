package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「把读回来的那个键发回去」必须存得进去 —— 读写不同名的两处。
 *
 * <p>运营端的表单同时用一个键读（编辑抽屉预填）和写（保存），所以读出参与写入参
 * 一旦不同名，那一格就**填了存不进去**：读侧自洽、写侧自洽，只有这条来回不通。
 * 两条都是 `check-form-fields` 给表单挂上端点后当场报出来的。
 *
 * <ul>
 *   <li><b>巡检计划的负责人</b>：读出参 {@code InspectionPlan.assignee}，
 *       而写入面此前直接收实体（字段 {@code assigneeNo}，列 {@code assignee_id}）
 *       → 计划开出来的工单没人担；</li>
 *   <li><b>分佣规则的计费基准</b>：读出参 {@code AgentCommission.basis}（GMV/ORDER_COUNT），
 *       而写入面此前叫 {@code dimension} → 规则静默落到默认值上。</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReadWriteFieldNameParityTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    final List<String> plans = new ArrayList<>();
    final List<String> rules = new ArrayList<>();

    @AfterAll
    void cleanup() {
        for (String p : plans) jdbc.update("DELETE FROM wo_inspection_plan WHERE plan_no=?", p);
        for (String r : rules) jdbc.update("DELETE FROM agt_commission WHERE rule_no=?", r);
    }

    @Test
    @DisplayName("★★ 巡检计划：负责人存得进 assignee_id，读回来还是 assignee")
    void inspectionAssigneeRoundTrips() {
        String admin = login("ADMIN");
        String no = "WIP_T" + (System.nanoTime() % 1000000);
        plans.add(no);

        Map<String, Object> m = new HashMap<>();
        m.put("planNo", no);
        m.put("route", "读写同名测试路线");
        m.put("frequency", "WEEKLY");
        m.put("cron", "0 0 9 * * MON");
        m.put("assignee", "EMP-0001");
        m.put("active", true);
        JsonNode created = post("/api/ops/inspection-plans", m, admin).okData();

        assertThat(created.path("assignee").asText())
                .as("出参就该带回负责人 —— 此前这里是空的，而 HTTP 仍然 200").isEqualTo("EMP-0001");
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM wo_inspection_plan WHERE plan_no=?",
                String.class, no)).isEqualTo("EMP-0001");
    }

    @Test
    @DisplayName("巡检计划：执行留痕三列不接受客户端提交 —— 它们归「立即执行一次」那条路")
    void inspectionRunTraceIsNotWritable() {
        String admin = login("ADMIN");
        String no = "WIP_R" + (System.nanoTime() % 1000000);
        plans.add(no);

        Map<String, Object> m = new HashMap<>();
        m.put("planNo", no);
        m.put("route", "留痕白名单测试");
        m.put("frequency", "DAILY");
        m.put("cron", "0 0 9 * * *");
        m.put("assignee", "EMP-0001");
        m.put("active", true);
        m.put("lastRunPeriod", "2020-01");        // 冒充执行过
        m.put("lastRunWoNos", List.of("WO-FAKE"));
        post("/api/ops/inspection-plans", m, admin).okData();

        assertThat(jdbc.queryForObject("SELECT last_run_period FROM wo_inspection_plan WHERE plan_no=?",
                String.class, no))
                .as("执行留痕由 run 那条路写；入参白名单里没有它，就不需要谁记得去拦")
                .isNull();
    }

    @Test
    @DisplayName("★★ 分佣规则：计费基准存得进 dimension，读回来还是 basis")
    void commissionBasisRoundTrips() {
        String admin = login("ADMIN");
        String agentNo = jdbc.queryForObject("SELECT agent_no FROM agt_agent WHERE deleted=0 ORDER BY id LIMIT 1",
                String.class);
        String no = "ACM_T" + (System.nanoTime() % 1000000);
        rules.add(no);

        Map<String, Object> m = new HashMap<>();
        m.put("ruleNo", no);
        m.put("agentNo", agentNo);
        m.put("basis", "ORDER_COUNT");
        m.put("mode", "LEDGER");
        // ORDER_COUNT 维度必须给 fixedAmount（checkDimension 在保存那一刻就挡）——
        // 第一版这里给的是 rate，于是回 400；而那个 400 本身就说明 basis 真的到了 dimension，
        // 否则维度会是空的、根本走不到这条校验
        m.put("fixedAmount", 0.5);
        m.put("currency", "AED");
        m.put("effectiveAt", java.time.LocalDate.now().toString());
        JsonNode created = post("/api/agent/commissions", m, admin).okData();

        assertThat(created.path("basis").asText())
                .as("出参就该带回计费基准 —— 此前发 basis、后端读 dimension，静默落默认值")
                .isEqualTo("ORDER_COUNT");
        assertThat(jdbc.queryForObject("SELECT dimension FROM agt_commission WHERE rule_no=?", String.class, no))
                .as("落库的列是 dimension").isEqualTo("ORDER_COUNT");
    }
}
