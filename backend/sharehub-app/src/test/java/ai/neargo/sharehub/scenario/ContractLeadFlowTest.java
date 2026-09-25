package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.alarm.engine.AlarmEngine;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.LeadTickResult;
import ai.neargo.sharehub.loc.ext.service.LeadOpsService;
import ai.neargo.sharehub.loc.service.ContractService;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行清单批次 B：合同到期提醒（B1）· 终止审批（B2）· 财务会签（B3）· 补充协议（B4）·
 * 商机阶段机（B5）· 查重（B6）· 提醒与线索池（B7）· 签约转化（B8）· 竞品到期重新激活（B9）。
 *
 * <p><b>全局扫描的定时（商机 tick、合同 tick）一律在回滚事务里跑、在同一事务里断言</b>：
 * 它们会扫到共享测试库里的种子数据，提交出去就把别人的商机回收进线索池了。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContractLeadFlowTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ContractService contracts;
    @Autowired
    LeadOpsService leadOps;
    @Autowired
    AlarmEngine engine;
    @Autowired
    PlatformTransactionManager tm;

    String bd;
    String admin;
    String finance;

    final List<String> siteNos = new ArrayList<>();
    final List<String> contractNos = new ArrayList<>();
    final List<String> leadNos = new ArrayList<>();
    final List<String> venueNos = new ArrayList<>();

    @BeforeAll
    void tokens() {
        bd = login("BD");
        admin = login("ADMIN");
        finance = login("FINANCE");
    }

    @AfterAll
    void cleanup() {
        for (String c : contractNos) {
            for (String a : jdbc.queryForList("SELECT alarm_no FROM dev_alarm WHERE subject_no=?", String.class, c)) {
                for (String t : List.of("dev_alarm_log", "dev_alarm_todo", "dev_alarm_notice")) {
                    jdbc.update("DELETE FROM " + t + " WHERE alarm_no=?", a);
                }
                jdbc.update("DELETE FROM dev_alarm WHERE alarm_no=?", a);
            }
            jdbc.update("DELETE FROM dev_alarm_condition WHERE dedup_key LIKE ?", "%:" + c);
        }
        for (String s : siteNos) {
            for (String c : jdbc.queryForList("SELECT contract_no FROM loc_contract WHERE site_no=?", String.class, s)) {
                jdbc.update("DELETE FROM loc_contract_log WHERE contract_no=?", c);
                jdbc.update("DELETE FROM loc_contract_attach WHERE contract_no=?", c);
                jdbc.update("DELETE FROM loc_contract WHERE contract_no=?", c);
            }
            jdbc.update("DELETE FROM loc_site_status_log WHERE site_no=?", s);
            jdbc.update("DELETE FROM loc_site WHERE site_no=?", s);
        }
        for (String l : leadNos) {
            jdbc.update("DELETE FROM loc_lead_follow WHERE lead_no=?", l);
            jdbc.update("DELETE FROM loc_lead WHERE lead_no=?", l);
        }
        for (String v : venueNos) jdbc.update("DELETE FROM loc_venue WHERE venue_no=?", v);
    }

    // ───────────────────────── 合同 ─────────────────────────

    @Test
    @DisplayName("B1 到期提醒：60 天内开一条（LOW，BD 待办）→ 跨进 30 天升 MEDIUM → 续签合同提交后恢复")
    void expiryReminder() {
        String site = newSite();
        String no = activeContract(site, LocalDate.now().minusMonths(6), LocalDate.now().plusDays(50), 0.2);
        LocalDateTime t0 = LocalDate.now().atTime(12, 0);

        engine.tickSites(t0, List.of(site));
        Map<String, Object> a = jdbc.queryForMap("SELECT alarm_no, priority, status, site_no FROM dev_alarm WHERE subject_no=? AND alarm_code='CONTRACT_EXPIRING'", no);
        assertThat(a).containsEntry("priority", "LOW").containsEntry("status", "OPEN").containsEntry("site_no", site);
        String alarmNo = (String) a.get("alarm_no");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dev_alarm_todo WHERE alarm_no=? AND status='OPEN'", Integer.class, alarmNo))
                .as("BD 待办一张").isEqualTo(1);

        jdbc.update("UPDATE loc_contract SET end_at=? WHERE contract_no=?", LocalDate.now().plusDays(20).toString(), no);
        engine.tickSites(t0.plusMinutes(1), List.of(site));
        assertThat(jdbc.queryForObject("SELECT priority FROM dev_alarm WHERE alarm_no=?", String.class, alarmNo)).isEqualTo("MEDIUM");
        assertThat(logEvents(alarmNo)).contains("IMPACT_UP");
        engine.tickSites(t0.plusMinutes(2), List.of(site));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dev_alarm WHERE subject_no=? AND alarm_code='CONTRACT_EXPIRING'",
                Integer.class, no)).as("同一合同只一条").isEqualTo(1);

        // 续签提交 → 已有人在处理，不再成立
        String renewal = post("/api/ops/contracts/" + no + "/renew", Map.of(), bd).okData().path("contractNo").asText();
        post("/api/ops/contracts/" + renewal + "/submit", Map.of(), bd).okData();
        engine.tickSites(t0.plusMinutes(3), List.of(site));
        assertThat(logEvents(alarmNo)).contains("RECOVER");
    }

    @Test
    @DisplayName("B3 条件加签：分成 0.4 > 0.30 → 运营通过后仍 PENDING（FINANCE）；运营再审 409；同一审批人会签 409；财务会签 → SIGNED")
    void financeCosign() {
        String site = newSite();
        Map<String, Object> body = body(site, LocalDate.now().plusDays(3), LocalDate.now().plusYears(1));
        body.put("shareRate", 0.4);
        String no = post("/api/ops/contracts", body, bd).okData().path("contractNo").asText();
        contractNos.add(no);
        post("/api/ops/contracts/" + no + "/submit", Map.of(), bd).okData();

        JsonNode c = post("/api/ops/contracts/" + no + "/audit", Map.of("result", "APPROVE"), admin).okData();
        assertThat(c.path("status").asText()).isEqualTo("PENDING");
        assertThat(c.path("flow").path("auditStage").asText()).isEqualTo("FINANCE");
        assertThat(post("/api/ops/contracts/" + no + "/audit", Map.of("result", "APPROVE"), admin).status).isEqualTo(409);
        assertThat(get("/api/ops/contracts/summary", finance).okData().path("pendingCosign").asLong()).isGreaterThanOrEqualTo(1);

        assertThat(post("/api/ops/contracts/" + no + "/cosign", Map.of("result", "APPROVE"), bd).status)
                .as("提交人不能会签自己的合同").isEqualTo(409);
        assertThat(post("/api/ops/contracts/" + no + "/cosign", Map.of("result", "REJECT"), finance).status)
                .as("驳回要原因").isEqualTo(400);
        JsonNode done = post("/api/ops/contracts/" + no + "/cosign", Map.of("result", "APPROVE", "reason", "比例在区域上限内"), finance).okData();
        assertThat(done.path("status").asText()).isEqualTo("SIGNED");
        assertThat(done.path("flow").path("financeAuditedBy").asText()).isNotBlank();
        assertThat(logs(no)).containsSubsequence("SUBMIT", "APPROVE", "COSIGN");
    }

    @Test
    @DisplayName("B2 提前终止走审批：申请后仍 ACTIVE；重复申请 409；自审 409；他人批准 → 到终止日定时终止；当日终止批准即终止")
    void terminationApproval() {
        String site = newSite();
        String no = activeContract(site, LocalDate.now().minusMonths(1), LocalDate.now().plusYears(1), 0.2);
        LocalDate eff = LocalDate.now().plusDays(10);
        JsonNode c = post("/api/ops/contracts/" + no + "/terminate", Map.of("reason", "场地装修", "effectiveAt", eff.toString()), bd).okData();
        assertThat(c.path("status").asText()).as("审批中不影响营业").isEqualTo("ACTIVE");
        assertThat(c.path("flow").path("termination").path("status").asText()).isEqualTo("PENDING");
        assertThat(post("/api/ops/contracts/" + no + "/terminate", Map.of("reason", "再来一次", "effectiveAt", eff.toString()), bd).status)
                .isEqualTo(409);
        assertThat(post("/api/ops/contracts/" + no + "/termination/audit", Map.of("result", "APPROVE"), bd).status).isEqualTo(409);
        assertThat(post("/api/ops/contracts/" + no + "/termination/audit", Map.of("result", "APPROVE"), admin).okData()
                .path("flow").path("termination").path("status").asText()).isEqualTo("APPROVED");
        assertThat(status(no)).isEqualTo("ACTIVE");

        inRollback(st -> {
            contracts.tick(eff.minusDays(1));
            assertThat(status(no)).isEqualTo("ACTIVE");
            assertThat(contracts.tick(eff).terminated()).isGreaterThanOrEqualTo(1);
            assertThat(status(no)).isEqualTo("TERMINATED");
        });

        // 驳回留痕，驳回后可以重新申请；终止日为今天 → 批准即终止
        String other = activeContract(newSite(), LocalDate.now().minusMonths(1), LocalDate.now().plusYears(1), 0.2);
        post("/api/ops/contracts/" + other + "/terminate", Map.of("reason", "a", "effectiveAt", LocalDate.now().toString()), bd).okData();
        assertThat(post("/api/ops/contracts/" + other + "/termination/audit", Map.of("result", "REJECT"), admin).status).isEqualTo(400);
        post("/api/ops/contracts/" + other + "/termination/audit", Map.of("result", "REJECT", "reason", "先谈续约"), admin).okData();
        post("/api/ops/contracts/" + other + "/terminate", Map.of("reason", "谈崩了", "effectiveAt", LocalDate.now().toString()), bd).okData();
        assertThat(post("/api/ops/contracts/" + other + "/termination/audit", Map.of("result", "APPROVE"), admin).okData()
                .path("status").asText()).isEqualTo("TERMINATED");
        assertThat(logs(other)).containsSubsequence("TERM_REQUEST", "TERM_REJECT", "TERM_REQUEST", "TERM_APPROVE", "TERMINATE");
    }

    @Test
    @DisplayName("B4 补充协议：生成关联草稿（重复 409）→ 改条款 → 审批签署 → 生效日原合同 EXPIRED（被补充协议取代），不发签约事件")
    void supplement() {
        String site = newSite();
        LocalDate end = LocalDate.now().plusYears(1);
        String parent = activeContract(site, LocalDate.now().minusMonths(2), end, 0.2);
        LocalDate start = LocalDate.now().plusDays(1);
        JsonNode s = post("/api/ops/contracts/" + parent + "/supplement", Map.of("startAt", start.toString()), bd).okData();
        String no = s.path("contractNo").asText();
        contractNos.add(no);
        assertThat(s.path("status").asText()).isEqualTo("DRAFT");
        assertThat(s.path("flow").path("contractKind").asText()).isEqualTo("SUPPLEMENT");
        assertThat(s.path("flow").path("parentContractNo").asText()).isEqualTo(parent);
        assertThat(s.path("endAt").asText()).isEqualTo(end.toString());
        assertThat(post("/api/ops/contracts/" + parent + "/supplement", Map.of(), bd).status).isEqualTo(409);

        Map<String, Object> edit = body(site, start, end);
        edit.put("shareRate", 0.25);
        post("/api/ops/contracts/" + no, edit, bd).okData();
        post("/api/ops/contracts/" + no + "/submit", Map.of(), bd).okData();
        post("/api/ops/contracts/" + no + "/audit", Map.of("result", "APPROVE"), admin).okData();
        String fileNo = uploadFile("CONTRACT_SCAN", "supplement.pdf", minimalPdf(), bd).path("fileNo").asText();
        assertThat(post("/api/ops/contracts/" + no + "/sign", Map.of("signedAt", LocalDate.now().toString(), "fileNos", List.of(fileNo)), bd)
                .okData().path("status").asText()).isEqualTo("SIGNED");

        inRollback(st -> {
            contracts.tick(start);
            assertThat(status(no)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForMap("SELECT status, end_reason FROM loc_contract WHERE contract_no=?", parent))
                    .containsEntry("status", "EXPIRED").containsEntry("end_reason", "被补充协议 " + no + " 替代");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE event_type='CONTRACT_SIGNED' AND aggregate_id=?",
                    Integer.class, no)).as("补充协议不是新签，不付牵线费").isZero();
        });
    }

    // ───────────────────────── 商机 ─────────────────────────

    @Test
    @DisplayName("B5 阶段机：NEW 不能跳到 NEGOTIATING；跟进推到 CONTACTED；LOST 无原因 400；有原因 → LOST；重新激活 → NEW")
    void leadStages() {
        String no = lead(Map.of("venueName", uniq("阶段测试场地")), bd).path("leadNo").asText();
        assertThat(post("/api/ops/leads/" + no, Map.of("stage", "NEGOTIATING"), bd).status).isEqualTo(400);
        post("/api/ops/leads/" + no + "/follow-ups", Map.of("channel", "CALL", "toStage", "CONTACTED", "content", "首次电话"), bd).okData();
        assertThat(get("/api/ops/leads/" + no, bd).okData().path("stage").asText()).isEqualTo("CONTACTED");
        assertThat(post("/api/ops/leads/" + no, Map.of("stage", "LOST"), bd).status).isEqualTo(400);
        JsonNode lost = post("/api/ops/leads/" + no, Map.of("stage", "LOST", "lostReason", "已签竞品",
                "competitorName", "某竞品", "competitorExclusiveUntil", LocalDate.now().plusMonths(8).toString()), bd).okData();
        assertThat(lost.path("stage").asText()).isEqualTo("LOST");
        assertThat(lost.path("lostAt").isNull()).isFalse();
        JsonNode back = post("/api/ops/leads/" + no, Map.of("stage", "NEW"), bd).okData();
        assertThat(back.path("stage").asText()).isEqualTo("NEW");
        assertThat(back.path("lostReason").asText()).as("丢单原因作历史保留").isEqualTo("已签竞品");
        assertThat(back.path("reactivatedAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("B6 查重：同一场地名 / 地址，他人 90 天内在跟 → 409 并提示负责人；自己重复录不拦")
    void leadDedup() {
        String name = uniq("查重场地");
        String first = lead(Map.of("venueName", name, "address", name + " 1 号"), bd).path("leadNo").asText();
        Resp dup = post("/api/ops/leads", Map.of("venueName", "  " + name.toUpperCase() + " "), admin);
        assertThat(dup.status).isEqualTo(409);
        assertThat(dup.msg()).contains(first);
        assertThat(post("/api/ops/leads", Map.of("venueName", uniq("别的名字"), "address", name + " 1 号"), admin).status)
                .as("地址相同也算").isEqualTo(409);
        leadNos.add(lead(Map.of("venueName", name), bd).path("leadNo").asText());
    }

    @Test
    @DisplayName("B7 提醒与线索池：沉默 10 天提醒一次（不重复）；沉默 40 天回收进池；池里不能跟进；认领后重新计时；二次认领 409")
    void followupAndPool() {
        String quiet = lead(Map.of("venueName", uniq("提醒场地")), bd).path("leadNo").asText();
        String stale = lead(Map.of("venueName", uniq("回收场地")), bd).path("leadNo").asText();
        String owner = jdbc.queryForObject("SELECT owner FROM loc_lead WHERE lead_no=?", String.class, stale);
        jdbc.update("UPDATE loc_lead SET last_follow_at=? WHERE lead_no=?", LocalDateTime.now().minusDays(10), quiet);
        jdbc.update("UPDATE loc_lead SET last_follow_at=? WHERE lead_no=?", LocalDateTime.now().minusDays(40), stale);

        inRollback(st -> {
            LeadTickResult r = leadOps.tick(LocalDateTime.now());
            assertThat(r.reminded()).isGreaterThanOrEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT remind_at IS NOT NULL FROM loc_lead WHERE lead_no=?", Boolean.class, quiet)).isTrue();
            assertThat(jdbc.queryForMap("SELECT in_pool, owner, prev_owner FROM loc_lead WHERE lead_no=?", stale))
                    .containsEntry("in_pool", true).containsEntry("owner", null).containsEntry("prev_owner", owner);
            LocalDateTime firstRemind = jdbc.queryForObject("SELECT remind_at FROM loc_lead WHERE lead_no=?", LocalDateTime.class, quiet);
            leadOps.tick(LocalDateTime.now().plusMinutes(1));
            assertThat(jdbc.queryForObject("SELECT remind_at FROM loc_lead WHERE lead_no=?", LocalDateTime.class, quiet))
                    .as("同一段沉默只提醒一次").isEqualTo(firstRemind);
        });

        // 池的行为走 HTTP：直接置池后验证跟进 / 认领
        jdbc.update("UPDATE loc_lead SET in_pool=1, owner=NULL, prev_owner=? WHERE lead_no=?", owner, stale);
        assertThat(post("/api/ops/leads/" + stale + "/follow-ups", Map.of("channel", "CALL", "content", "试试"), bd).status).isEqualTo(409);
        JsonNode claimed = post("/api/ops/leads/" + stale + "/claim", Map.of(), admin).okData();
        assertThat(claimed.path("inPool").asBoolean()).isFalse();
        assertThat(claimed.path("owner").asText()).isNotBlank().isNotEqualTo(owner);
        assertThat(post("/api/ops/leads/" + stale + "/claim", Map.of(), bd).status).isEqualTo(409);
    }

    @Test
    @DisplayName("B8 签约转化：一次生成场地方 + 筹备中站点 + 带谈判条款的合同草稿（记来源商机），商机 SIGNED；二次转化 409")
    void convert() {
        Map<String, Object> m = new HashMap<>();
        m.put("venueName", uniq("转化场地"));
        m.put("stage", "NEGOTIATING");
        m.put("regionId", "R-TEST");
        m.put("address", "测试路 8 号");
        m.put("shareMode", "SHARE");
        m.put("shareRate", 0.22);
        m.put("termMonths", 6);
        String no = lead(m, bd).path("leadNo").asText();
        LocalDate start = LocalDate.now().plusDays(7);
        JsonNode r = post("/api/ops/leads/" + no + "/convert", Map.of("openHours", "10:00-22:00", "startAt", start.toString()), bd).okData();
        venueNos.add(r.path("venueNo").asText());
        siteNos.add(r.path("siteNo").asText());
        assertThat(r.path("venueCreated").asBoolean()).isTrue();
        assertThat(r.path("siteCreated").asBoolean()).isTrue();

        Map<String, Object> c = jdbc.queryForMap("SELECT status, site_no, source_lead_no, share_rate, start_at, end_at FROM loc_contract WHERE contract_no=?",
                r.path("contractNo").asText());
        assertThat(c).containsEntry("status", "DRAFT").containsEntry("source_lead_no", no).containsEntry("site_no", r.path("siteNo").asText());
        assertThat(((Number) c.get("share_rate")).doubleValue()).isEqualTo(0.22);
        assertThat(c.get("end_at").toString()).isEqualTo(start.plusMonths(6).minusDays(1).toString());
        assertThat(jdbc.queryForObject("SELECT status FROM loc_site WHERE site_no=?", String.class, r.path("siteNo").asText())).isEqualTo("PREPARING");

        JsonNode lead = get("/api/ops/leads/" + no, bd).okData();
        assertThat(lead.path("stage").asText()).isEqualTo("SIGNED");
        assertThat(lead.path("contractNo").asText()).isEqualTo(r.path("contractNo").asText());
        assertThat(post("/api/ops/leads/" + no + "/convert", Map.of("openHours", "10:00-22:00"), bd).status).isEqualTo(409);
        assertThat(post("/api/ops/leads/" + no, Map.of("contractNo", "FORGED"), bd).okData().path("contractNo").asText())
                .as("转化结果不能经编辑改写").isEqualTo(r.path("contractNo").asText());
    }

    @Test
    @DisplayName("B9 竞品独家 30 天后到期（< 60 天）→ LOST 商机重新激活为 NEW，只激活一次")
    void competitorReactivation() {
        String no = lead(Map.of("venueName", uniq("竞品场地")), bd).path("leadNo").asText();
        jdbc.update("UPDATE loc_lead SET stage='LOST', lost_reason='已签竞品', lost_at=?, competitor_exclusive_until=? WHERE lead_no=?",
                LocalDateTime.now().minusDays(100), LocalDate.now().plusDays(30), no);
        inRollback(st -> {
            assertThat(leadOps.tick(LocalDateTime.now()).reactivated()).isGreaterThanOrEqualTo(1);
            assertThat(jdbc.queryForMap("SELECT stage, reactivated_at IS NOT NULL AS r FROM loc_lead WHERE lead_no=?", no))
                    .containsEntry("stage", "NEW");
            jdbc.update("UPDATE loc_lead SET stage='LOST' WHERE lead_no=?", no);   // 又被人手动丢回来：lost_at 没变，不再自动激活
            leadOps.tick(LocalDateTime.now().plusMinutes(1));
            assertThat(jdbc.queryForObject("SELECT stage FROM loc_lead WHERE lead_no=?", String.class, no)).isEqualTo("LOST");
        });
    }

    // ───────────────────────── 夹具 ─────────────────────────

    private void inRollback(Consumer<org.springframework.transaction.TransactionStatus> body) {
        new TransactionTemplate(tm).executeWithoutResult(st -> {
            try {
                body.accept(st);
            } finally {
                st.setRollbackOnly();
            }
        });
    }

    private JsonNode lead(Map<String, Object> body, String token) {
        JsonNode l = post("/api/ops/leads", body, token).okData();
        leadNos.add(l.path("leadNo").asText());
        return l;
    }

    private List<String> logs(String contractNo) {
        return jdbc.queryForList("SELECT event FROM loc_contract_log WHERE contract_no=? ORDER BY id", String.class, contractNo);
    }

    private List<String> logEvents(String alarmNo) {
        return jdbc.queryForList("SELECT event FROM dev_alarm_log WHERE alarm_no=? ORDER BY id", String.class, alarmNo);
    }

    private String status(String no) {
        return jdbc.queryForObject("SELECT status FROM loc_contract WHERE contract_no=?", String.class, no);
    }

    private String newSite() {
        String siteNo = "STB" + rnd();
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours, region_id) VALUES (?, 'MAIN', ?, ?, 'ACTIVE', '00:00-23:59', 'R-TEST')",
                siteNo, venue, "批次B测试站点 " + siteNo);
        siteNos.add(siteNo);
        return siteNo;
    }

    /** 直接落一份生效合同（审批链路另有用例覆盖）。 */
    private String activeContract(String site, LocalDate start, LocalDate end, double rate) {
        String no = "CTB" + rnd();
        jdbc.update("INSERT INTO loc_contract (contract_no, tenant_id, venue_no, site_no, share_rate, entry_fee, start_at, end_at, status, contract_kind)"
                        + " VALUES (?, 'MAIN', (SELECT venue_no FROM loc_site WHERE site_no=?), ?, ?, 0, ?, ?, 'ACTIVE', 'MAIN')",
                no, site, site, rate, start.toString(), end.toString());
        contractNos.add(no);
        return no;
    }

    private Map<String, Object> body(String site, LocalDate start, LocalDate end) {
        Map<String, Object> m = new HashMap<>();
        m.put("venueNo", jdbc.queryForObject("SELECT venue_no FROM loc_site WHERE site_no=?", String.class, site));
        m.put("siteNo", site);
        m.put("shareMode", "SHARE");
        m.put("shareRate", 0.2);
        m.put("startAt", start.toString());
        m.put("endAt", end.toString());
        return m;
    }

    private static String uniq(String prefix) {
        return prefix + " " + rnd();
    }

    private static String rnd() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
