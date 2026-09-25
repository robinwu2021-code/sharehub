package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.api.core.event.CabinetWentLiveEvent;
import ai.neargo.sharehub.loc.service.SiteService;
import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 站点状态机（TDD-运营核心流程/03 §九）。每个用例自建站点，不依赖共享库里的历史站点。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SiteStatusMachineTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    org.springframework.transaction.PlatformTransactionManager tm;
    @Autowired
    SiteService sites;
    @Autowired
    ApplicationEventPublisher publisher;

    String admin;
    final java.util.List<String> cabinets = new java.util.ArrayList<>();

    @AfterAll
    void cleanup() {
        ai.neargo.sharehub.support.FixtureCleanup.dropCabinets(jdbc, cabinets);
    }

    @BeforeAll
    void tokens() {
        admin = login("ADMIN");
    }

    @Test
    @DisplayName("① 建档 → PREPARING、日志 CREATE；编辑带 status / agentNo 被忽略")
    void createIsPreparing() {
        JsonNode s = create();
        String no = s.path("siteNo").asText();
        assertThat(s.path("status").asText()).isEqualTo("PREPARING");
        assertThat(get("/api/ops/sites/" + no + "/status-logs", admin).okData().findValuesAsText("event")).containsExactly("CREATE");

        Map<String, Object> body = body();
        body.put("status", "ACTIVE");
        body.put("agentNo", "AG999");
        JsonNode u = post("/api/ops/sites/" + no, body, admin).okData();
        assertThat(u.path("status").asText()).isEqualTo("PREPARING");
        assertThat(u.path("agentNo").isNull() || u.path("agentNo").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("② 首台机柜上线事件 → ACTIVE、first_live_at 有值；重复事件不重复写日志")
    void goLiveByEvent() {
        String no = create().path("siteNo").asText();
        publisher.publishEvent(new CabinetWentLiveEvent("CABX", no, null, LocalDateTime.now().toString()));
        publisher.publishEvent(new CabinetWentLiveEvent("CABX", no, null, LocalDateTime.now().toString()));
        assertThat(status(no)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT first_live_at IS NOT NULL FROM loc_site WHERE site_no=?", Boolean.class, no)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM loc_site_status_log WHERE site_no=? AND event='GO_LIVE'",
                Integer.class, no)).isEqualTo(1);
    }

    @Test
    @DisplayName("③ 事件丢失：站点下已有 DEPLOYED 机柜，跑对账 → ACTIVE；开业清单设备项通过")
    void reconcileByTick() {
        String no = create().path("siteNo").asText();
        cabinet(no, "DEPLOYED");
        assertThat(get("/api/ops/sites/" + no + "/opening-checklist", admin).okData().path("items").findValuesAsText("key"))
                .containsExactly("SURVEY", "CONTRACT", "LOCATION", "OPS_OWNER", "OPEN_HOURS", "DEVICE_LIVE");
        // 对账是全局扫描（还会自动关闭满足条件的撤场站点）：放进回滚事务，别动共享库里别人的站点
        new org.springframework.transaction.support.TransactionTemplate(tm).executeWithoutResult(st -> {
            sites.tick();
            assertThat(status(no)).isEqualTo("ACTIVE");
            st.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("④ 暂停 → 无生效合同时恢复 409；暂停缺原因 400")
    void resumeNeedsContract() {
        String no = create().path("siteNo").asText();
        sites.goLiveIfPreparing(no, LocalDateTime.now(), "test");
        assertThat(post("/api/ops/sites/" + no + "/pause", Map.of(), admin).status).isEqualTo(400);
        assertThat(post("/api/ops/sites/" + no + "/pause", Map.of("reason", "装修", "pauseUntil", LocalDate.now().plusDays(3).toString()),
                admin).okData().path("status").asText()).isEqualTo("PAUSED");
        assertThat(post("/api/ops/sites/" + no + "/resume", Map.of(), admin).status).isEqualTo(409);
        assertThat(status(no)).isEqualTo("PAUSED");
    }

    @Test
    @DisplayName("⑤ 关闭：尚有 1 台设备、1 张未结工单 → 409；门禁两项未通过且带数量；归档非 CLOSED → 409")
    void closeGate() {
        String no = create().path("siteNo").asText();
        cabinet(no, "IN_STOCK");
        jdbc.update("INSERT INTO wo_order (wo_no, tenant_id, type, source, site_no, status) VALUES (?, 'MAIN', 'FAULT', 'MANUAL', ?, 'CREATED')",
                "WOT" + rnd(), no);
        JsonNode gate = get("/api/ops/sites/" + no + "/close-gate", admin).okData();
        assertThat(gate.path("allPassed").asBoolean()).isFalse();
        assertThat(gate.path("items").get(0).path("detail").asText()).contains("1 台");
        assertThat(gate.path("items").get(1).path("detail").asText()).contains("1 张");
        assertThat(gate.path("items").get(2).path("passed").asBoolean()).isTrue();
        assertThat(post("/api/ops/sites/" + no + "/close", Map.of("note", "撤了"), admin).status).isEqualTo(409);
        assertThat(post("/api/ops/sites/" + no + "/archive", Map.of(), admin).status).isEqualTo(409);

        String empty = create().path("siteNo").asText();
        assertThat(post("/api/ops/sites/" + empty + "/close", Map.of("note", "没谈成"), admin).okData().path("status").asText())
                .isEqualTo("CLOSED");
        assertThat(post("/api/ops/sites/" + empty + "/archive", Map.of(), admin).okData().path("archivedAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("撤场：营业中站点发起撤场 → WITHDRAWING，每台已布放的机柜各开一张撤机单（重复发起不重复开）")
    void withdrawOpensRemovalOrders() {
        String no = create().path("siteNo").asText();
        cabinet(no, "DEPLOYED");
        cabinet(no, "DEPLOYED");
        sites.goLiveIfPreparing(no, LocalDateTime.now(), "test");
        assertThat(post("/api/ops/sites/" + no + "/withdraw", Map.of("reason", "场地方不续约", "plannedAt", LocalDate.now().plusDays(7).toString()),
                admin).okData().path("status").asText()).isEqualTo("WITHDRAWING");
        assertThat(jdbc.queryForList("SELECT type FROM wo_order WHERE source_ref LIKE ?", String.class, "WD:" + no + ":%"))
                .hasSize(2).containsOnly("REMOVE");
        assertThat(post("/api/ops/sites/" + no + "/close", Map.of("note", "还没撤完"), admin).status).isEqualTo(409);
    }

    @Test
    @DisplayName("⑧ 旧的阶段流转端点已删；生命周期改为只读漏斗")
    void lifecycleReadOnly() {
        assertThat(post("/api/ops/site-lifecycles/ST1/stage", Map.of("toStage", "CLOSED"), admin).status).isIn(401, 403, 404, 405, 409);   // 旧端点保留为 409 桩（运营端旧页面迁移前不 404）   // 未映射路径被安全链 fail-closed 拦下也算「已删」
        assertThat(get("/api/ops/site-lifecycles/funnel", admin).okData().isArray()).isTrue();
        assertThat(get("/api/ops/site-lifecycles?phase=PREPARING", admin).okData().path("total").asLong()).isPositive();
    }

    // —— 夹具 ——

    private JsonNode create() {
        return post("/api/ops/sites", body(), admin).okData();
    }

    private Map<String, Object> body() {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "状态机测试站点 " + rnd());
        m.put("venueNo", jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class));
        m.put("regionId", "R-TEST");
        m.put("openHours", "10:00-22:00");
        return m;
    }

    private void cabinet(String siteNo, String status) {
        String no = "CBT" + rnd();
        cabinets.add(no);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, site_no, status, slot_total) VALUES (?, 'MAIN', ?, 'TEST', ?, ?, 8)",
                no, no, siteNo, status);
    }

    private String status(String no) {
        return jdbc.queryForObject("SELECT status FROM loc_site WHERE site_no=?", String.class, no);
    }

    private static String rnd() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
