package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.alarm.engine.AlarmEngine;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.DeviceEvent;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.DeviceEventBatch;
import ai.neargo.sharehub.dev.service.DeviceSignalService;
import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务告警（TDD-运营核心流程/05 §十一）+ 工单承接（06 §六）。
 *
 * <p>时间经 {@code tickSites(now, 本用例站点)} 推进：判定用的「此刻」是参数，持续条件按参数累计；
 * 只判本用例自建的站点 —— 全量 tick 会给共享库里心跳早已停止的种子柜开告警、挂停借，污染别的用例。
 * 在线与否仍按真实心跳时间判（离线 = 心跳早于真实当前 3 分钟），夹具直接把心跳写旧。
 * tick 会扫共享库里的全部站点，所以断言只看本用例自建的站点与告警。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BusinessAlarmTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AlarmEngine engine;
    @Autowired
    DeviceSignalService signals;

    String admin;
    final java.util.List<String> cabinets = new java.util.ArrayList<>();

    @AfterAll
    void cleanup() {
        ai.neargo.sharehub.support.FixtureCleanup.dropCabinets(jdbc, cabinets);
    }
    /** 固定在正午：营业时间 00:00-23:59 内，且不受真实时刻影响。 */
    final LocalDateTime t0 = LocalDate.now().atTime(12, 0);

    @BeforeAll
    void tokens() {
        admin = login("ADMIN");
    }

    @Test
    @DisplayName("① 单柜站点离线满 10 分钟 → 整站借不到 / 还不了各一条（URGENT、OFFLINE），合并成一张 FAULT 工单；告警持有整柜停借")
    void offlineSite() {
        String site = site(true);
        jdbc.update("UPDATE loc_site SET ops_employee_no='E1001' WHERE site_no=?", site);
        String cab = cabinet(site, false);
        engine.tickSites(t0, List.of(site));
        engine.tickSites(t0.plusMinutes(5), List.of(site));
        assertThat(open(site)).isEmpty();                           // 计时中，未满 10 分钟
        engine.tickSites(t0.plusMinutes(10), List.of(site));
        List<Map<String, Object>> alarms = open(site);
        assertThat(alarms).extracting(m -> m.get("alarm_code")).containsExactlyInAnyOrder("SITE_UNRENTABLE", "SITE_UNRETURNABLE");
        assertThat(alarms).allSatisfy(m -> {
            assertThat(m.get("cause")).isEqualTo("OFFLINE");
            assertThat(m.get("priority")).isEqualTo("URGENT");     // 基准 HIGH + 整站 1
        });
        assertThat(alarms).extracting(m -> m.get("disposition_ref")).doesNotContainNull().containsOnly(alarms.get(0).get("disposition_ref"));
        String woNo = (String) alarms.get(0).get("disposition_ref");
        assertThat(jdbc.queryForObject("SELECT type FROM wo_order WHERE wo_no=?", String.class, woNo)).isEqualTo("FAULT");
        // 派给站点运维责任人；告警成立推送给同一个人（规则 target=SITE_OWNER，正午不在静默窗口）
        assertThat(jdbc.queryForMap("SELECT status, assignee_name, dispatch_strategy FROM wo_order WHERE wo_no=?", woNo))
                .containsEntry("status", "DISPATCHED").containsEntry("assignee_name", "E1001").containsEntry("dispatch_strategy", "OWNER");
        assertThat(jdbc.queryForList("SELECT target FROM dev_alarm_notice WHERE alarm_no=? AND status='SENT'", String.class,
                alarms.get(0).get("alarm_no"))).containsExactly("E1001");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dev_protection WHERE cabinet_no=? AND holder_type='ALARM' AND action='STOP_RENT' AND active=1",
                Integer.class, cab)).isGreaterThanOrEqualTo(1);

        // ④⑤ 恢复：心跳回来 → 防抖 10 分钟后 SELF_HEALED；尚未接单的工单撤销；停借释放
        jdbc.update("UPDATE dev_cabinet SET last_heartbeat_at=NOW(3), online_status='ONLINE' WHERE cabinet_no=?", cab);
        engine.tickSites(t0.plusMinutes(11), List.of(site));
        assertThat(open(site)).allSatisfy(m -> assertThat(m.get("recovered_at")).isNotNull());
        engine.tickSites(t0.plusMinutes(22), List.of(site));
        assertThat(open(site)).isEmpty();
        assertThat(jdbc.queryForList("SELECT close_reason FROM dev_alarm WHERE site_no=? AND source='EVAL'", String.class, site))
                .containsOnly("SELF_HEALED");
        assertThat(jdbc.queryForObject("SELECT CONCAT(status,'/',close_reason) FROM wo_order WHERE wo_no=?", String.class, woNo))
                .isEqualTo("CLOSED/WITHDRAWN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dev_protection WHERE cabinet_no=? AND holder_type='ALARM' AND active=1",
                Integer.class, cab)).isZero();
    }

    @Test
    @DisplayName("通知升级：整站借不到成立后 30 分钟仍未确认 → 推给运维主管（OPS）；同一事件不重复推")
    void escalation() {
        String site = site(true);
        cabinet(site, false);
        for (int m = 0; m <= 10; m += 5) engine.tickSites(t0.plusMinutes(m), List.of(site));
        String alarmNo = jdbc.queryForObject("SELECT alarm_no FROM dev_alarm WHERE site_no=? AND alarm_code='SITE_UNRENTABLE' AND status='OPEN'",
                String.class, site);
        // 升级时限从「条件首次出现」算（t0），不是从告警成立（t0+10）算 —— 用户受影响是从那一刻开始的
        engine.tickSites(t0.plusMinutes(25), List.of(site));
        assertThat(escalations(alarmNo)).as("25 分钟，还没到 30 分钟的升级时限").isZero();
        engine.tickSites(t0.plusMinutes(31), List.of(site));
        int first = escalations(alarmNo);
        assertThat(first).as("种子里 OPS 在职员工 E1001 / E1002").isGreaterThanOrEqualTo(2);
        engine.tickSites(t0.plusMinutes(32), List.of(site));
        assertThat(escalations(alarmNo)).as("幂等：同一事件对同一人只推一次").isEqualTo(first);

        assertThat(get("/api/ops/alarms/codes/stats?days=1", admin).okData().findValuesAsText("code")).contains("SITE_UNRENTABLE");
    }

    private int escalations(String alarmNo) {
        // 本用例站点没有运维责任人，成立通知一条也发不出去 —— 发给 OPS 的只可能是升级
        return jdbc.queryForObject("SELECT COUNT(*) FROM dev_alarm_notice WHERE alarm_no=? AND target IN ('E1001','E1002')",
                Integer.class, alarmNo);
    }

    @Test
    @DisplayName("⑥ 单柜告警后整站也借不到 → 柜级被取代（SUPERSEDED，parent 指向站点级）")
    void supersede() {
        String site = site(true);
        String a = cabinet(site, false);
        cabinet(site, true);
        for (int m = 0; m <= 10; m += 5) engine.tickSites(t0.plusMinutes(m), List.of(site));
        List<Map<String, Object>> first = open(site);
        assertThat(first).extracting(m -> m.get("alarm_code")).contains("CABINET_UNRENTABLE");
        String cabinetAlarm = first.stream().filter(m -> "CABINET_UNRENTABLE".equals(m.get("alarm_code"))).map(m -> (String) m.get("alarm_no"))
                .findFirst().orElseThrow();

        jdbc.update("UPDATE dev_cabinet SET last_heartbeat_at=NOW(3) - INTERVAL 1 HOUR WHERE site_no=?", site);
        for (int m = 15; m <= 25; m += 5) engine.tickSites(t0.plusMinutes(m), List.of(site));
        String upper = jdbc.queryForObject("SELECT alarm_no FROM dev_alarm WHERE site_no=? AND alarm_code='SITE_UNRENTABLE' AND status='OPEN'",
                String.class, site);
        assertThat(jdbc.queryForMap("SELECT status, close_reason, parent_alarm_no FROM dev_alarm WHERE alarm_no=?", cabinetAlarm))
                .containsEntry("status", "CLOSED").containsEntry("close_reason", "SUPERSEDED").containsEntry("parent_alarm_no", upper);
        assertThat(a).isNotNull();
    }

    @Test
    @DisplayName("⑪ 无合同在营业 → BD 待办；待办完成但合同未补 → 告警不关、次轮新待办；补上合同 → 自愈关闭、待办撤销")
    void siteWithoutContract() {
        String site = site(false);
        engine.tickSites(t0, List.of(site));
        Map<String, Object> alarm = jdbc.queryForMap("SELECT alarm_no, disposition_type, disposition_ref FROM dev_alarm WHERE site_no=? AND alarm_code='SITE_WITHOUT_CONTRACT' AND status='OPEN'", site);
        assertThat(alarm.get("disposition_type")).isEqualTo("TODO");
        String todo1 = (String) alarm.get("disposition_ref");
        assertThat(jdbc.queryForObject("SELECT role_code FROM dev_alarm_todo WHERE todo_no=?", String.class, todo1)).isEqualTo("BD");

        post("/api/ops/alarm-todos/" + todo1 + "/done", Map.of("note", "已联系"), admin).okData();
        assertThat(jdbc.queryForObject("SELECT status FROM dev_alarm WHERE alarm_no=?", String.class, alarm.get("alarm_no"))).isEqualTo("OPEN");
        engine.tickSites(t0.plusMinutes(1), List.of(site));
        String todo2 = jdbc.queryForObject("SELECT disposition_ref FROM dev_alarm WHERE alarm_no=?", String.class, alarm.get("alarm_no"));
        assertThat(todo2).isNotNull().isNotEqualTo(todo1);

        contract(site);
        engine.tickSites(t0.plusMinutes(2), List.of(site));
        assertThat(jdbc.queryForObject("SELECT CONCAT(status,'/',close_reason) FROM dev_alarm WHERE alarm_no=?", String.class, alarm.get("alarm_no")))
                .isEqualTo("CLOSED/SELF_HEALED");
        assertThat(jdbc.queryForObject("SELECT status FROM dev_alarm_todo WHERE todo_no=?", String.class, todo2)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("⑩ 电池异常 → BATTERY_HAZARD URGENT + 工单；人工 RESOLVED 关闭 409；完工复核通过 → 自动验收、告警关闭、锁仓解除")
    void batteryHazard() {
        String site = site(true);
        String cab = cabinet(site, true);
        signals.ingest(new DeviceEventBatch(List.of(new DeviceEvent("E" + rnd(), "BATTERY_ABNORMAL", cab, 3, null, "TEST", "0xBA",
                LocalDateTime.now(), Map.of()))));
        Map<String, Object> a = jdbc.queryForMap("SELECT alarm_no, priority, disposition_ref FROM dev_alarm WHERE cabinet_no=? AND alarm_code='BATTERY_HAZARD' AND status='OPEN'", cab);
        assertThat(a.get("priority")).isEqualTo("URGENT");
        String woNo = (String) a.get("disposition_ref");
        assertThat(woNo).isNotNull();
        assertThat(post("/api/ops/alarms/records/" + a.get("alarm_no") + "/close", Map.of("reason", "RESOLVED", "note", "修了"), admin).status)
                .isEqualTo(409);

        post("/api/ops/work-orders/" + woNo + "/dispatch", Map.of("assignee", "E0001"), admin).okData();
        post("/api/ops/work-orders/" + woNo + "/accept", Map.of(), admin).okData();
        post("/api/ops/work-orders/" + woNo + "/handle", Map.of("handleNote", "到场"), admin).okData();
        assertThat(post("/api/ops/work-orders/" + woNo + "/complete", Map.of("handleNote", "换电池", "faultReasonCode", "BATTERY"), admin).status)
                .isEqualTo(400);                                    // 故障单没有现场照片
        post("/api/ops/work-orders/" + woNo + "/complete", Map.of("handleNote", "换电池", "faultReasonCode", "BATTERY", "photos", "[\"p1.jpg\"]"), admin).okData();

        assertThat(jdbc.queryForMap("SELECT status, review_status, audited_by FROM wo_order WHERE wo_no=?", woNo))
                .containsEntry("status", "CLOSED").containsEntry("review_status", "PASSED").containsEntry("audited_by", "SYSTEM");
        assertThat(jdbc.queryForObject("SELECT CONCAT(status,'/',close_reason) FROM dev_alarm WHERE alarm_no=?", String.class, a.get("alarm_no")))
                .isEqualTo("CLOSED/RESOLVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dev_protection WHERE cabinet_no=? AND action='SLOT_LOCK' AND active=1", Integer.class, cab))
                .isZero();

        // 工单详情：时间线含完工与复核、带出关联告警；列表按站点筛且带关联告警数
        var detail = get("/api/ops/work-orders/" + woNo, admin).okData();
        assertThat(detail.path("timeline").findValuesAsText("action")).contains("DISPATCH", "ACCEPT", "COMPLETE", "REVIEW");
        assertThat(detail.path("alarms").get(0).path("alarmNo").asText()).isEqualTo(a.get("alarm_no"));
        assertThat(detail.at("/order/ops/reviewStatus").asText()).isEqualTo("PASSED");
        var list = get("/api/ops/work-orders?siteNo=" + site, admin).okData().path("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).at("/ops/alarmCount").asInt()).isEqualTo(1);
        assertThat(list.get(0).at("/ops/faultReasonCode").asText()).isEqualTo("BATTERY");
    }

    @Test
    @DisplayName("⑧ 出宝失败的订单 → RENT_NOT_DELIVERED 满 5 分钟 → 自愈撤单 → AUTO_FIXED")
    void rentNotDelivered() {
        String site = site(true);
        String cab = cabinet(site, true);
        String order = "ORDT" + rnd();
        jdbc.update("INSERT INTO ord_order (order_no, tenant_id, c_user_no, cabinet_no, site_no, status, started_at, currency) VALUES"
                + " (?, 'MAIN', 'CU-ALARM-TEST', ?, ?, 'DISPENSING', NOW(3) - INTERVAL 10 MINUTE, 'AED')", order, cab, site);
        engine.tickSites(t0, List.of(site));
        engine.tickSites(t0.plusMinutes(5), List.of(site));
        assertThat(jdbc.queryForObject("SELECT CONCAT(status,'/',close_reason) FROM dev_alarm WHERE subject_no=? AND alarm_code='RENT_NOT_DELIVERED'",
                String.class, order)).isEqualTo("CLOSED/AUTO_FIXED");
        assertThat(jdbc.queryForObject("SELECT status FROM ord_order WHERE order_no=?", String.class, order)).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("工单：同合并键两次开单只一张、更高优先级上调；撤单 CLOSED(WITHDRAWN)；已派单撤单通知被派人")
    void workOrderMergeAndWithdraw() {
        var wo = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> woOps);
        String key = "ALM:TEST" + rnd() + ":FAULT";
        String a = wo.openOrAttach(new ai.neargo.sharehub.wo.ext.dto.WoExtDtos.AlarmDraft("FAULT", "MEDIUM", key, null, null, "测试 1", "A1"));
        String b = wo.openOrAttach(new ai.neargo.sharehub.wo.ext.dto.WoExtDtos.AlarmDraft("FAULT", "URGENT", key, null, null, "测试 2", "A2"));
        assertThat(b).isEqualTo(a);
        assertThat(jdbc.queryForObject("SELECT priority FROM wo_order WHERE wo_no=?", String.class, a)).isEqualTo("URGENT");
        wo.withdraw(a, "关联告警已自动恢复");
        assertThat(jdbc.queryForObject("SELECT CONCAT(status,'/',close_reason) FROM wo_order WHERE wo_no=?", String.class, a))
                .isEqualTo("CLOSED/WITHDRAWN");
        String c = wo.openOrAttach(new ai.neargo.sharehub.wo.ext.dto.WoExtDtos.AlarmDraft("FAULT", "LOW", key, null, null, "复发", "A3"));
        assertThat(c).isNotEqualTo(a);                              // 完结后复发 = 新问题、新单
        assertThat(jdbc.queryForObject("SELECT source_ref FROM wo_order WHERE wo_no=?", String.class, c)).isEqualTo(key + "#2");
    }

    @Autowired
    ai.neargo.sharehub.wo.ext.service.WoOpsService woOps;

    // —— 夹具 ——

    private String site(boolean withContract) {
        String site = "STA" + rnd();
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours, region_id) VALUES (?, 'MAIN', ?, ?, 'ACTIVE', '00:00-23:59', 'R-TEST')",
                site, venue, "告警测试站点 " + site);
        if (withContract) contract(site);
        return site;
    }

    private void contract(String site) {
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_site WHERE site_no=?", String.class, site);
        jdbc.update("INSERT INTO loc_contract (contract_no, tenant_id, venue_no, site_no, share_rate, start_at, end_at, status) VALUES (?, 'MAIN', ?, ?, 0.2, ?, ?, 'ACTIVE')",
                "CTA" + rnd(), venue, site, LocalDate.now().minusDays(1).toString(), LocalDate.now().plusYears(1).toString());
    }

    /** 已布放机柜，8 仓、1 块可借宝；online=false 时心跳写成 1 小时前（离线）。 */
    private String cabinet(String site, boolean online) {
        String cab = "CBA" + rnd();
        cabinets.add(cab);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, site_no, slot_total, available_count, status,"
                        + " online_status, last_heartbeat_at) VALUES (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, 8, 1, 'DEPLOYED', 'ONLINE', "
                        + (online ? "NOW(3)" : "NOW(3) - INTERVAL 1 HOUR") + ")",
                cab, cab, site);
        return cab;
    }

    private List<Map<String, Object>> open(String site) {
        return jdbc.queryForList("SELECT alarm_no, alarm_code, cause, priority, disposition_ref, recovered_at FROM dev_alarm"
                + " WHERE site_no=? AND source='EVAL' AND status IN ('OPEN','ACKED')", site);
    }

    private static String rnd() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
