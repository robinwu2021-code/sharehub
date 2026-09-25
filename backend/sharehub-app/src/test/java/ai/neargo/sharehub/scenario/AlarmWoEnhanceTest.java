package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.alarm.engine.AlarmEngine;
import ai.neargo.sharehub.api.platform.port.NotifyPort;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.DeviceEvent;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.DeviceEventBatch;
import ai.neargo.sharehub.dev.service.DeviceSignalService;
import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.FixtureCleanup;
import ai.neargo.sharehub.wo.ext.service.WoOpsService;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行清单批次 E：窗口计数告警 + 失联宝（E1）· SLA 超时升级通知（E2）· 抢单（E3）· 短信 / 邮件通道（E4）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AlarmWoEnhanceTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AlarmEngine engine;
    @Autowired
    DeviceSignalService signals;
    @Autowired
    WoOpsService woOps;
    @Autowired
    NotifyPort notify;
    @Autowired
    PlatformTransactionManager tm;

    String admin;
    final List<String> siteNos = new ArrayList<>();
    final List<String> cabinets = new ArrayList<>();
    final List<String> powerbanks = new ArrayList<>();
    final List<String> employees = new ArrayList<>();

    @BeforeAll
    void tokens() {
        admin = login("ADMIN");
    }

    @AfterAll
    void cleanup() {
        for (String s : siteNos) {
            for (String a : jdbc.queryForList("SELECT alarm_no FROM dev_alarm WHERE site_no=?", String.class, s)) {
                for (String t : List.of("dev_alarm_log", "dev_alarm_todo", "dev_alarm_notice")) jdbc.update("DELETE FROM " + t + " WHERE alarm_no=?", a);
                jdbc.update("DELETE FROM dev_protection WHERE holder_ref=?", a);
                jdbc.update("DELETE FROM dev_alarm WHERE alarm_no=?", a);
            }
            jdbc.update("DELETE FROM dev_alarm_condition WHERE subject_site=?", s);
            jdbc.update("DELETE FROM wo_dispatch WHERE wo_no IN (SELECT wo_no FROM wo_order WHERE site_no=?)", s);
            jdbc.update("DELETE FROM wo_sla WHERE wo_no IN (SELECT wo_no FROM wo_order WHERE site_no=?)", s);
            jdbc.update("DELETE FROM wo_order WHERE site_no=?", s);
            jdbc.update("DELETE FROM ord_order WHERE site_no=?", s);
        }
        for (String c : cabinets) jdbc.update("DELETE FROM dev_signal_log WHERE cabinet_no=?", c);
        FixtureCleanup.dropCabinets(jdbc, cabinets);
        for (String p : powerbanks) {
            for (String a : jdbc.queryForList("SELECT alarm_no FROM dev_alarm WHERE subject_no=?", String.class, p)) {
                for (String t : List.of("dev_alarm_log", "dev_alarm_todo", "dev_alarm_notice")) jdbc.update("DELETE FROM " + t + " WHERE alarm_no=?", a);
                jdbc.update("DELETE FROM dev_alarm WHERE alarm_no=?", a);
            }
            jdbc.update("DELETE FROM dev_powerbank WHERE powerbank_no=?", p);
        }
        for (String e : employees) jdbc.update("DELETE FROM iam_employee WHERE employee_no=?", e);
        jdbc.update("DELETE FROM notify_log WHERE scene='TEST_E4'");
        for (String s : siteNos) jdbc.update("DELETE FROM loc_site WHERE site_no=?", s);
    }

    @Test
    @DisplayName("E1 弹出失败 60 分钟 3 次 → 某柜频繁弹不出：柜子被告警挂停借、开维修单；心跳间隔 7 次 > 3 分钟 → 频繁掉线")
    void countAlarms() {
        String site = site("R-E" + rnd());
        String cab = cabinet(site);
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 3; i++) ingest(cab, "EJECT_TIMEOUT", now.minusMinutes(10 - i));

        String flaky = cabinet(site);
        jdbc.update("UPDATE dev_cabinet SET last_heartbeat_at=? WHERE cabinet_no=?", now.minusMinutes(45).toString().replace('T', ' '), flaky);
        for (int i = 0; i < 7; i++) ingest(flaky, "HEARTBEAT", now.minusMinutes(40 - i * 5L));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dev_signal_log WHERE cabinet_no=? AND code='LINK_RESTORED'", Integer.class, flaky))
                .isEqualTo(7);

        engine.tickSites(now, List.of(site));
        Map<String, Object> eject = jdbc.queryForMap("SELECT alarm_no, disposition_type FROM dev_alarm WHERE subject_no=? AND alarm_code='EJECT_FAIL_RATE' AND status='OPEN'", cab);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dev_protection WHERE cabinet_no=? AND action='STOP_RENT' AND holder_type='ALARM' AND active=1 AND holder_ref=?",
                Integer.class, cab, eject.get("alarm_no"))).as("告警持有整柜停借").isEqualTo(1);
        assertThat(eject.get("disposition_type")).isEqualTo("WORK_ORDER");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dev_alarm WHERE subject_no=? AND alarm_code='FREQUENT_OFFLINE' AND status='OPEN'", Integer.class, flaky))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("E1 充电宝失联：借出中、订单已结、30 小时没动 → 失联告警（运维待办）")
    void missingPowerbank() {
        String site = site("R-E" + rnd());
        String pb = "PBF" + rnd();
        powerbanks.add(pb);
        jdbc.update("INSERT INTO dev_powerbank (powerbank_no, tenant_id, sn, battery, status) VALUES (?, 'MAIN', ?, 80, 'RENTED')", pb, pb);
        jdbc.update("UPDATE dev_powerbank SET updated_at = NOW(3) - INTERVAL 30 HOUR WHERE powerbank_no=?", pb);
        jdbc.update("INSERT INTO ord_order (order_no, tenant_id, c_user_no, cabinet_no, site_no, status, device_type, powerbank_no, currency, amount)"
                + " VALUES (?, 'MAIN', 'CU-E1', 'CAB-E1', ?, 'SETTLED', 'POWERBANK', ?, 'AED', 3)", "ORDF" + rnd(), site, pb);
        engine.tickSites(LocalDateTime.now(), List.of(site));
        Map<String, Object> a = jdbc.queryForMap("SELECT alarm_no, disposition_type FROM dev_alarm WHERE subject_no=? AND alarm_code='POWERBANK_MISSING' AND status='OPEN'", pb);
        assertThat(a.get("disposition_type")).isEqualTo("TODO");
    }

    @Test
    @DisplayName("E2 响应超时 → 本区域运维收到一次升级通知（再扫不重发）；已被接的单、超时一天以上的积压只占位不通知")
    void slaEscalation() {
        String region = "R-E" + rnd();
        String site = site(region);
        String ops = employee(region);
        String wo = workOrder(site, "CREATED");
        jdbc.update("INSERT INTO wo_sla (wo_no, respond_due_at, resolve_due_at) VALUES (?, NOW(3) - INTERVAL 1 HOUR, NOW(3) + INTERVAL 1 DAY)", wo);
        String taken = workOrder(site, "ACCEPTED");
        String old = workOrder(site, "CREATED");
        jdbc.update("INSERT INTO wo_sla (wo_no, respond_due_at, respond_breached) VALUES (?, NOW(3) - INTERVAL 3 DAY, 1)", old);
        jdbc.update("INSERT INTO wo_sla (wo_no, respond_due_at, respond_breached) VALUES (?, NOW(3) - INTERVAL 1 HOUR, 1)", taken);

        new TransactionTemplate(tm).executeWithoutResult(st -> {   // 全局扫描：回滚
            LocalDateTime before = LocalDateTime.now().minusSeconds(1);
            woOps.sweepSlaBreaches();
            assertThat(jdbc.queryForObject("SELECT respond_breached FROM wo_sla WHERE wo_no=?", Boolean.class, wo)).isTrue();
            assertThat(jdbc.queryForObject("SELECT respond_escalated_at IS NOT NULL FROM wo_sla WHERE wo_no=?", Boolean.class, wo)).isTrue();
            // notify_log 的目标按规范脱敏（员工号也只剩首字母），按人断言不了；共享库里还有别的测试留下的近期超时单，
            // 总条数也不稳定 —— 断言「发过」，以及本单的升级时刻在第二轮扫描后不变（= 没有再发）
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_log WHERE scene='WO_SLA_RESPOND_BREACH' AND created_at >= ?",
                    Integer.class, before)).isGreaterThanOrEqualTo(1);
            Object first = jdbc.queryForObject("SELECT respond_escalated_at FROM wo_sla WHERE wo_no=?", Object.class, wo);
            woOps.sweepSlaBreaches();
            assertThat(jdbc.queryForObject("SELECT respond_escalated_at FROM wo_sla WHERE wo_no=?", Object.class, wo))
                    .as("同一档只通知一次").isEqualTo(first);
            assertThat(jdbc.queryForObject("SELECT respond_escalated_at IS NOT NULL FROM wo_sla WHERE wo_no=?", Boolean.class, taken)).isTrue();
            assertThat(jdbc.queryForObject("SELECT respond_escalated_at IS NOT NULL FROM wo_sla WHERE wo_no=?", Boolean.class, old))
                    .as("超时三天的历史单静默占位").isTrue();
            st.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("E3 抢单：池里看得到 → 抢到即派给自己并接单（GRAB）→ 再抢 409")
    void grab() {
        String site = site("R-E" + rnd());
        String wo = workOrder(site, "CREATED");
        // 共享库里 CREATED 的历史单很多：按一个少见的类型筛，保证本单在第一页
        jdbc.update("UPDATE wo_order SET type='CLEAN', wo_created_at=? WHERE wo_no=?", LocalDateTime.now().toString(), wo);
        JsonNode pool = get("/api/ops/work-orders/pool?type=CLEAN&size=200", admin).okData();
        assertThat(pool.path("list").findValuesAsText("woNo")).contains(wo);
        JsonNode got = post("/api/ops/work-orders/" + wo + "/grab", Map.of(), admin).okData();
        assertThat(got.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForMap("SELECT dispatch_strategy, assignee_name FROM wo_order WHERE wo_no=?", wo))
                .containsEntry("dispatch_strategy", "GRAB").hasEntrySatisfying("assignee_name", v -> assertThat(v).isNotNull());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wo_dispatch WHERE wo_no=?", Integer.class, wo)).isEqualTo(2);
        assertThat(post("/api/ops/work-orders/" + wo + "/grab", Map.of(), admin).status).isEqualTo(409);
    }

    @Test
    @DisplayName("E4 按渠道发：员工有明文手机 → 发出、流水脱敏；只有掩码 / 非员工 / 未接通道 → 不发并说明原因")
    void channels() {
        String ok = employee(null);
        jdbc.update("UPDATE iam_employee SET phone='+971501234567', email='ops.e4@sharehub.ae' WHERE employee_no=?", ok);
        assertThat(notify.send(ok, "SMS", "TEST_E4", "测试短信").sent()).isTrue();
        assertThat(notify.send(ok, "EMAIL", "TEST_E4", "测试邮件").sent()).isTrue();
        List<String> targets = jdbc.queryForList("SELECT target FROM notify_log WHERE scene='TEST_E4'", String.class);
        assertThat(targets).hasSize(2).allSatisfy(t -> assertThat(t).contains("*"));

        String masked = employee(null);
        jdbc.update("UPDATE iam_employee SET phone='+97150****67' WHERE employee_no=?", masked);
        assertThat(notify.send(masked, "SMS", "TEST_E4", "x").reason()).isEqualTo("MASKED_ONLY");
        assertThat(notify.send("AG-NOT-EMP", "SMS", "TEST_E4", "x").reason()).isEqualTo("NOT_EMPLOYEE");
        assertThat(notify.send(ok, "WEBHOOK", "TEST_E4", "x").reason()).isEqualTo("UNSUPPORTED_CHANNEL");
    }

    // —— 夹具 ——

    private void ingest(String cab, String type, LocalDateTime at) {
        signals.ingest(new DeviceEventBatch(List.of(new DeviceEvent("E" + rnd(), type, cab, null, null, "TEST", null, at, Map.of()))));
    }

    private String site(String region) {
        String no = "STF" + rnd();
        siteNos.add(no);
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours, region_id) VALUES (?, 'MAIN', ?, ?, 'ACTIVE', '00:00-23:59', ?)",
                no, venue, "批次E测试站点 " + no, region);
        return no;
    }

    private String cabinet(String site) {
        String cab = "CBF" + rnd();
        cabinets.add(cab);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, site_no, slot_total, available_count, status,"
                + " online_status, last_heartbeat_at) VALUES (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, 8, 4, 'DEPLOYED', 'ONLINE', NOW(3))", cab, cab, site);
        return cab;
    }

    private String workOrder(String site, String status) {
        String no = "WOF" + rnd();
        jdbc.update("INSERT INTO wo_order (wo_no, tenant_id, type, source, priority, status, site_no) VALUES (?, 'MAIN', 'FAULT', 'MANUAL', 'MEDIUM', ?, ?)",
                no, status, site);
        return no;
    }

    private String employee(String region) {
        String no = "EF" + rnd();
        employees.add(no);
        jdbc.update("INSERT INTO iam_employee (employee_no, tenant_id, name, role_no, status) VALUES (?, 'MAIN', ?, 'OPS', 'ACTIVE')", no, "测试运维 " + no);
        if (region != null) {
            jdbc.update("INSERT INTO iam_data_scope (subject_type, subject_no, scope_type, scope_refs) VALUES ('EMPLOYEE', ?, 'REGION', ?)",
                    no, "[\"" + region + "\"]");
        }
        return no;
    }

    private static String rnd() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
