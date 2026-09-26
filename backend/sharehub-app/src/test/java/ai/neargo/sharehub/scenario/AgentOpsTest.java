package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.agent.dto.AgentDtos.Agent;
import ai.neargo.sharehub.agent.ext.service.AgentOpsAssessmentService;
import ai.neargo.sharehub.agent.service.AgentService;
import ai.neargo.sharehub.alarm.eval.AgentSlaEvaluator;
import ai.neargo.sharehub.alarm.eval.EvalScope;
import ai.neargo.sharehub.api.core.event.OrderSettledEvent;
import ai.neargo.sharehub.api.platform.port.AgentDirectoryPort;
import ai.neargo.sharehub.finance.dto.FinDtos.WithdrawApplyReq;
import ai.neargo.sharehub.finance.service.ShareGenerator;
import ai.neargo.sharehub.finance.service.WithdrawalService;
import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 执行清单批次 F：停用冻结提现（F1）· 停用改派工单（F2）· 清退（F3）· 平台接管（F4）· 月度考核与运维分成系数（F5）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentOpsTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AgentService agentService;
    @Autowired
    WithdrawalService withdrawals;
    @Autowired
    AgentDirectoryPort directory;
    @Autowired
    AgentOpsAssessmentService assessments;
    @Autowired
    AgentSlaEvaluator slaEvaluator;
    @Autowired
    ShareGenerator shareGenerator;
    @Autowired
    PlatformTransactionManager tm;

    String admin;
    final List<String> agents = new ArrayList<>();
    final List<String> sites = new ArrayList<>();
    final List<String> employees = new ArrayList<>();
    final List<String> cabinets = new ArrayList<>();

    @BeforeAll
    void tokens() {
        admin = login("ADMIN");
    }

    @AfterAll
    void cleanup() {
        for (String a : agents) {
            jdbc.update("DELETE FROM stl_withdrawal WHERE payee_no=?", a);
            jdbc.update("DELETE FROM share_record WHERE payee_no=?", a);
            jdbc.update("DELETE FROM share_rule WHERE payee_no=?", a);
            jdbc.update("DELETE FROM agt_exit WHERE agent_no=?", a);
            jdbc.update("DELETE FROM agt_ops_assessment WHERE agent_no=?", a);
            jdbc.update("DELETE FROM agt_account WHERE agent_no=?", a);
            jdbc.update("DELETE FROM loc_site_agent WHERE agent_no=?", a);
            jdbc.update("DELETE FROM wo_dispatch WHERE wo_no IN (SELECT wo_no FROM wo_order WHERE assignee_name=? OR taken_over_from=?)", a, a);
            jdbc.update("DELETE FROM wo_sla WHERE wo_no IN (SELECT wo_no FROM wo_order WHERE assignee_name=? OR taken_over_from=?)", a, a);
            jdbc.update("DELETE FROM wo_order WHERE assignee_name=? OR taken_over_from=?", a, a);
            jdbc.update("DELETE FROM agt_agent WHERE agent_no=?", a);
        }
        for (String s : sites) {
            jdbc.update("DELETE FROM wo_dispatch WHERE wo_no IN (SELECT wo_no FROM wo_order WHERE site_no=?)", s);
            jdbc.update("DELETE FROM wo_sla WHERE wo_no IN (SELECT wo_no FROM wo_order WHERE site_no=?)", s);
            jdbc.update("DELETE FROM wo_order WHERE site_no=?", s);
            jdbc.update("DELETE FROM loc_site WHERE site_no=?", s);
        }
        for (String c : cabinets) jdbc.update("DELETE FROM dev_cabinet WHERE cabinet_no=?", c);
        for (String e : employees) jdbc.update("DELETE FROM iam_employee WHERE employee_no=?", e);
    }

    @Test
    @DisplayName("F1 / F2 停用代理：名下已派 / 已接的工单改派站点员工责任人（REASSIGN）；提现申请与审批通过 409，驳回照常")
    void suspendFreezesAndReassigns() {
        String ag = agent();
        String emp = employee();
        String site = site(emp);
        String w1 = workOrder(site, ag, "DISPATCHED");
        String w2 = workOrder(site, ag, "ACCEPTED");
        String pending = "WDF" + rnd();
        jdbc.update("INSERT INTO stl_withdrawal (withdraw_no, tenant_id, payee_type, payee_no, amount, currency, status) VALUES (?, 'MAIN', 'AGENT', ?, 100, 'AED', 'APPLY')",
                pending, ag);

        setStatus(ag, "SUSPENDED");

        for (String w : List.of(w1, w2)) {
            assertThat(jdbc.queryForMap("SELECT status, assignee_name, assignee_type FROM wo_order WHERE wo_no=?", w))
                    .containsEntry("status", "DISPATCHED").containsEntry("assignee_name", emp).containsEntry("assignee_type", "EMPLOYEE");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wo_dispatch WHERE wo_no=? AND action='REASSIGN'", Integer.class, w)).isEqualTo(1);
        }
        assertThatThrownBy(() -> withdrawals.apply(new WithdrawApplyReq(null, "AGENT", ag, "测试代理", new BigDecimal("50"), "AED", null)))
                .hasMessage("error.withdrawal.agent_suspended");
        assertThatThrownBy(() -> withdrawals.audit(pending, true, null)).hasMessage("error.withdrawal.agent_suspended");
        assertThat(withdrawals.audit(pending, false, "代理停用，先驳回").status()).isNotEqualTo("APPLY");
    }

    @Test
    @DisplayName("F4 平台接管：未超时 409；非代理单 409；超时后接管 → 改派员工、记接管来源与 TAKEOVER 留痕")
    void takeover() {
        String ag = agent();
        String emp = employee();
        String site = site(emp);
        String wo = workOrder(site, ag, "PROCESSING");
        jdbc.update("INSERT INTO wo_sla (wo_no, resolve_due_at, resolve_breached) VALUES (?, NOW(3) + INTERVAL 1 DAY, 0)", wo);
        assertThat(post("/api/ops/work-orders/" + wo + "/takeover", Map.of(), admin).status).isEqualTo(409);
        jdbc.update("UPDATE wo_sla SET resolve_breached=1 WHERE wo_no=?", wo);
        JsonNode r = post("/api/ops/work-orders/" + wo + "/takeover", Map.of("reason", "代理两天没动"), admin).okData();
        assertThat(r.path("status").asText()).isEqualTo("DISPATCHED");
        assertThat(jdbc.queryForMap("SELECT assignee_name, taken_over_from FROM wo_order WHERE wo_no=?", wo))
                .containsEntry("assignee_name", emp).containsEntry("taken_over_from", ag);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wo_dispatch WHERE wo_no=? AND action='TAKEOVER'", Integer.class, wo)).isEqualTo(1);
        assertThat(post("/api/ops/work-orders/" + wo + "/takeover", Map.of(), admin).status).as("已经是员工的单").isEqualTo(409);
    }

    @Test
    @DisplayName("★★ 清退中的代理不能被改回启用——资产还没收完，业务不能再开")
    void exitingAgentCannotBeReEnabled() {
        String ag = agent();
        post("/api/agent/agents/" + ag + "/exit", Map.of("reason", "合作终止"), admin).okData();
        assertThat(jdbc.queryForObject("SELECT status FROM agt_agent WHERE agent_no=?", String.class, ag))
                .as("前提：开清退即停用").isEqualTo("SUSPENDED");

        // 实测（2026-09-25 接入）：这一下真把他改回 ENABLED 了。
        // AgentServiceImpl.save 只认 AgentStatus 词表，不看有没有在途清退单，
        // 于是资产还在回收、结算还没清，代理已经能继续开展业务。
        // 前端把按钮禁掉了，但**禁用按钮只是提示，闸得在服务端**。
        // shareRate / cabinetCount 必须带上：Agent 是 record，这两个是 double / int，
        // **缺字段就是 null→primitive**，Jackson 直接 400「请求有误」，连是哪个字段都不说。
        // 探针第一版少了它们，红在这里，看着像闸没生效 —— 实际请求根本没进 service。
        var resp = post("/api/agent/agents/" + ag,
                agentBody("测试代理 " + ag, "ENABLED"), admin);
        assertThat(resp.status).as("清退在途，启用要被拒（message=%s）", resp.msg()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT status FROM agt_agent WHERE agent_no=?", String.class, ag))
                .as("被拒之后状态不许动").isEqualTo("SUSPENDED");

        // 反向：改别的字段不该被这道闸连坐 —— 清退期间照样要能改联系人。
        assertThat(post("/api/agent/agents/" + ag, agentBody("改过的名字", "SUSPENDED"), admin).status)
                .as("不动状态的编辑照常放过").isEqualTo(200);
    }

    /** 代理档案的完整载荷。见上面那段注释：少一个基本类型字段就 400。 */
    private static Map<String, Object> agentBody(String name, String status) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("agentType", "AGENT");
        m.put("status", status);
        m.put("shareRate", 0.1);
        m.put("cabinetCount", 0);
        return m;
    }

    @Test
    @DisplayName("F3 清退：发起即停用；有柜子收不回 409；收回后进结清（此时放开提现）；有待出账分润 409；结清后关闭 → 账号停用、代理归档")
    void exitFlow() {
        String ag = agent();
        jdbc.update("INSERT INTO agt_account (account_no, tenant_id, agent_no, username, status) VALUES (?, 'MAIN', ?, ?, 'ACTIVE')",
                "AAF" + rnd(), ag, "exit." + ag.toLowerCase());
        String cab = "CBG" + rnd();
        cabinets.add(cab);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, agent_no, status) VALUES (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, 'IN_STOCK')",
                cab, cab, ag);

        JsonNode x = post("/api/agent/agents/" + ag + "/exit", Map.of("reason", "合作终止"), admin).okData();
        String exitNo = x.path("exitNo").asText();
        assertThat(x.path("status").asText()).isEqualTo("RECLAIMING");
        assertThat(jdbc.queryForObject("SELECT status FROM agt_agent WHERE agent_no=?", String.class, ag)).isEqualTo("SUSPENDED");
        assertThat(post("/api/agent/agents/" + ag + "/exit", Map.of("reason", "再来"), admin).status).isEqualTo(409);

        assertThat(post("/api/agent/exits/" + exitNo + "/advance", Map.of(), admin).status).isEqualTo(409);
        jdbc.update("UPDATE dev_cabinet SET agent_no=NULL WHERE cabinet_no=?", cab);
        assertThat(post("/api/agent/exits/" + exitNo + "/advance", Map.of(), admin).okData().path("status").asText()).isEqualTo("SETTLING");
        assertThat(directory.briefOf(ag).settlingExit()).as("结清中放开提现").isTrue();

        String rec = "SRF" + rnd();
        jdbc.update("INSERT INTO share_record (record_no, tenant_id, order_no, payee_type, payee_no, amount, currency, mode, dimension, rate, status, period, basis)"
                + " VALUES (?, 'MAIN', ?, 'AGENT', ?, 5, 'AED', 'LEDGER', 'AGENT', 0.1, 'PENDING', '2026-09', 'OPERATE')", rec, "ORD" + rnd(), ag);
        JsonNode gate = get("/api/agent/exits/" + exitNo + "/gate", admin).okData();
        assertThat(gate.path("allPassed").asBoolean()).isFalse();
        assertThat(post("/api/agent/exits/" + exitNo + "/advance", Map.of(), admin).status).isEqualTo(409);
        jdbc.update("UPDATE share_record SET status='DONE' WHERE record_no=?", rec);
        assertThat(post("/api/agent/exits/" + exitNo + "/advance", Map.of(), admin).okData().path("status").asText()).isEqualTo("CLOSING");
        assertThat(post("/api/agent/exits/" + exitNo + "/advance", Map.of(), admin).okData().path("status").asText()).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT status FROM agt_account WHERE agent_no=?", String.class, ag)).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("SELECT archived_at IS NOT NULL FROM agt_agent WHERE agent_no=?", Boolean.class, ag)).isTrue();
    }

    @Test
    @DisplayName("F5 月度考核：完结 3 单（1 单超时）+ 被接管 1 单 → 达成率 0.5 → 系数 0.80；次月 OPERATE 分润按系数打折；不达标告警成立")
    void assessment() {
        String ag = agent();
        String emp = employee();
        String site = site(emp);
        YearMonth last = YearMonth.now().minusMonths(1);
        LocalDateTime inLast = last.atDay(10).atTime(10, 0);
        String a = workOrder(site, ag, "CLOSED"), b = workOrder(site, ag, "CLOSED"), c = workOrder(site, ag, "CLOSED");
        String taken = workOrder(site, emp, "CLOSED");
        jdbc.update("UPDATE wo_order SET created_at=? WHERE wo_no IN (?, ?, ?, ?)", inLast, a, b, c, taken);
        jdbc.update("UPDATE wo_order SET taken_over_from=?, taken_over_at=? WHERE wo_no=?", ag, inLast, taken);
        jdbc.update("INSERT INTO wo_sla (wo_no, resolve_breached) VALUES (?, 0), (?, 1)", a, b);   // c 没有 SLA 行：视为达成
        jdbc.update("INSERT INTO loc_site_agent (tenant_id, site_no, agent_no, role) VALUES ('MAIN', ?, ?, 'OPERATE')", site, ag);
        jdbc.update("INSERT INTO share_rule (rule_no, tenant_id, dimension, payee_no, mode, rate, priority, basis) VALUES (?, 'MAIN', 'AGENT', ?, 'LEDGER', 0.2, 1, 'OPERATE')",
                "SRL" + rnd(), ag);

        new TransactionTemplate(tm).executeWithoutResult(st -> {   // 考核扫全部代理：回滚
            assessments.assess(last.toString());
            Map<String, Object> r = jdbc.queryForMap("SELECT wo_total, wo_in_sla, sla_rate, taken_over, coefficient, apply_period FROM agt_ops_assessment WHERE agent_no=? AND period=?",
                    ag, last.toString());
            assertThat(r).containsEntry("wo_total", 4).containsEntry("wo_in_sla", 2).containsEntry("taken_over", 1)
                    .containsEntry("apply_period", last.plusMonths(1).toString());
            assertThat((BigDecimal) r.get("sla_rate")).isEqualByComparingTo("0.5");
            assertThat((BigDecimal) r.get("coefficient")).isEqualByComparingTo("0.8");

            String order = "ORDG" + rnd();
            shareGenerator.generate(new OrderSettledEvent(order, null, site, ag, new BigDecimal("100"), "AED", last.plusMonths(1).toString()));
            assertThat(jdbc.queryForObject("SELECT rate FROM share_record WHERE order_no=? AND payee_no=? AND basis='OPERATE'", BigDecimal.class, order, ag))
                    .as("0.2 × 0.8").isEqualByComparingTo("0.16");

            assertThat(slaEvaluator.evaluate(new EvalScope(List.of()), LocalDateTime.now()))
                    .anySatisfy(f -> assertThat(f.subjectNo()).isEqualTo(ag));
            st.setRollbackOnly();
        });
    }

    // —— 夹具 ——

    private void setStatus(String ag, String status) {
        agentService.save(ag, new Agent(ag, "测试代理 " + ag, "联系人", null, "AGENT", 0.1, 0, status, null));
    }

    private String agent() {
        String no = "AGF" + rnd();
        agents.add(no);
        jdbc.update("INSERT INTO agt_agent (agent_no, tenant_id, name, status, agent_type) VALUES (?, 'MAIN', ?, 'ENABLED', 'AGENT')", no, "测试代理 " + no);
        return no;
    }

    private String employee() {
        String no = "EG" + rnd();
        employees.add(no);
        jdbc.update("INSERT INTO iam_employee (employee_no, tenant_id, name, role_no, status) VALUES (?, 'MAIN', ?, 'OPS', 'ACTIVE')", no, "测试运维 " + no);
        return no;
    }

    private String site(String opsEmployee) {
        String no = "STG" + rnd();
        sites.add(no);
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours, region_id, ops_employee_no) VALUES (?, 'MAIN', ?, ?, 'ACTIVE', '00:00-23:59', 'R-TEST', ?)",
                no, venue, "批次F测试站点 " + no, opsEmployee);
        return no;
    }

    private String workOrder(String site, String assignee, String status) {
        String no = "WOG" + rnd();
        jdbc.update("INSERT INTO wo_order (wo_no, tenant_id, type, source, priority, status, site_no, assignee_name, assignee_type) VALUES (?, 'MAIN', 'FAULT', 'MANUAL', 'MEDIUM', ?, ?, ?, ?)",
                no, status, site, assignee, assignee != null && assignee.startsWith("AG") ? "AGENT" : "EMPLOYEE");
        return no;
    }

    private static String rnd() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
