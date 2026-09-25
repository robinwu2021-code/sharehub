package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.alarm.engine.AlarmEngine;
import ai.neargo.sharehub.dev.port.PowerbankTracker;
import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.FixtureCleanup;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行清单批次 D：阈值调度（D1）· 低电堆积与老化（D2）· 按区域当天合并调度单（D3）· 巡检派生（D4）· 巡检清点（D5）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DispatchInspectionTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AlarmEngine engine;
    @Autowired
    PowerbankTracker tracker;
    @Autowired
    PlatformTransactionManager tm;

    String admin;
    final List<String> siteNos = new ArrayList<>();
    final List<String> cabinets = new ArrayList<>();
    final List<String> regions = new ArrayList<>();
    /** 固定在正午：营业时间 00:00-23:59 内。 */
    final LocalDateTime t0 = LocalDate.now().atTime(12, 0);

    @BeforeAll
    void tokens() {
        admin = login("ADMIN");
    }

    @AfterAll
    void cleanup() {
        for (String s : siteNos) {
            for (String a : jdbc.queryForList("SELECT alarm_no FROM dev_alarm WHERE site_no=?", String.class, s)) {
                for (String t : List.of("dev_alarm_log", "dev_alarm_todo", "dev_alarm_notice")) jdbc.update("DELETE FROM " + t + " WHERE alarm_no=?", a);
                jdbc.update("DELETE FROM dev_alarm WHERE alarm_no=?", a);
            }
            jdbc.update("DELETE FROM dev_alarm_condition WHERE subject_site=?", s);
            jdbc.update("DELETE FROM inv_asset_diff WHERE site_no=?", s);
            jdbc.update("DELETE FROM wo_handle WHERE wo_no IN (SELECT wo_no FROM wo_order WHERE site_no=?)", s);
            jdbc.update("DELETE FROM wo_order WHERE site_no=?", s);
        }
        for (String r : regions) {
            jdbc.update("DELETE FROM wo_handle WHERE wo_no IN (SELECT wo_no FROM wo_order WHERE source_ref LIKE ?)", "ALM:" + r + ":%");
            jdbc.update("DELETE FROM wo_order WHERE source_ref LIKE ?", "ALM:" + r + ":%");
        }
        FixtureCleanup.dropCabinets(jdbc, cabinets);
        for (String s : siteNos) jdbc.update("DELETE FROM loc_site WHERE site_no=?", s);
    }

    @Test
    @DisplayName("D1 / D2 / D3 两个站点：可借 1、空仓 1、低电堆积、老化宝、单柜无宝 → 各自成立；所有补宝 / 取宝 / 换宝并成一张区域调度单，查充电另开维修单")
    void thresholdDispatchMergedByRegion() {
        String region = "R-D" + rnd();
        regions.add(region);
        String s1 = site(region), s2 = site(region);
        String a = cabinet(s1, 10, 1);                 // 可借 1 → 可借不足
        powerbanks(a, 3, 90, false);
        String b = cabinet(s1, 4, 1);                  // 在柜 3 / 4 → 空仓 1（可还位不足）；低电 2 / 3 → 低电堆积
        powerbanks(b, 2, 20, false);
        powerbanks(b, 1, 90, false);
        String c = cabinet(s1, 8, 0);                  // 可借 0 → 单柜借不到（无宝）；2 颗老化宝
        powerbanks(c, 2, 90, true);
        String d = cabinet(s2, 10, 1);                 // 另一站点可借 1
        powerbanks(d, 2, 90, false);

        for (int m = 0; m <= 70; m += 5) engine.tickSites(t0.plusMinutes(m), List.of(s1, s2));

        Map<String, String> byCode = new java.util.HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT alarm_code, subject_no, disposition_ref FROM dev_alarm WHERE site_no IN (?, ?) AND status IN ('OPEN','ACKED')", s1, s2)) {
            byCode.put(r.get("alarm_code") + "@" + r.get("subject_no"), (String) r.get("disposition_ref"));
        }
        assertThat(byCode).containsKeys("RENTABLE_LOW@" + s1, "RENTABLE_LOW@" + s2, "RETURN_SPACE_LOW@" + s1,
                "LOW_BATTERY_PILEUP@" + b, "POWERBANK_AGED@" + c, "CABINET_UNRENTABLE@" + c);
        assertThat(byCode).doesNotContainKey("RENTABLE_LOW@" + c).as("可借 0 的柜子归借不到告警");

        String dispatch = byCode.get("RENTABLE_LOW@" + s1);
        assertThat(dispatch).isNotNull();
        assertThat(List.of(byCode.get("RENTABLE_LOW@" + s2), byCode.get("RETURN_SPACE_LOW@" + s1), byCode.get("POWERBANK_AGED@" + c),
                byCode.get("CABINET_UNRENTABLE@" + c))).as("补宝 / 取宝 / 换宝 / 无宝 同区域同日一张调度单").containsOnly(dispatch);
        assertThat(jdbc.queryForObject("SELECT type FROM wo_order WHERE wo_no=?", String.class, dispatch)).isEqualTo("REFILL");
        String repair = byCode.get("LOW_BATTERY_PILEUP@" + b);
        assertThat(repair).isNotNull().isNotEqualTo(dispatch);
        assertThat(jdbc.queryForObject("SELECT type FROM wo_order WHERE wo_no=?", String.class, repair)).isEqualTo("FAULT");
    }

    @Test
    @DisplayName("D2 老化标记：循环超限的宝标 AGED，挑宝跳过它、可借数回写不含它")
    void agingMarksAndSkips() {
        String site = site("R-D" + rnd());
        String cab = cabinet(site, 8, 2);
        String old = powerbank(cab, 1, 100, 900);
        String young = powerbank(cab, 2, 70, 50);
        new TransactionTemplate(tm).executeWithoutResult(st -> {   // 全局扫描：回滚，别动共享库里别人的宝
            assertThat(tracker.markAged(500)).isGreaterThanOrEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT health FROM dev_powerbank WHERE powerbank_no=?", String.class, old)).isEqualTo("AGED");
            assertThat(tracker.pickForRent(cab).powerbankNo()).as("电量最高的那颗老化了，挑下一颗").isEqualTo(young);
            assertThat(jdbc.queryForObject("SELECT available_count FROM dev_cabinet WHERE cabinet_no=?", Integer.class, cab)).isEqualTo(1);
            st.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("D4 巡检派生：来源 INSPECTION、来源号以巡检单号开头；同类重复派生幂等；非巡检单 / 未接单 409")
    void inspectionDerives() {
        String site = site("R-D" + rnd());
        String cab = cabinet(site, 8, 3);
        String insp = workOrder("INSPECT", "PROCESSING", cab, site);
        JsonNode child = post("/api/ops/work-orders/" + insp + "/derive", Map.of("type", "FAULT", "description", "3 号仓锁不回弹"), admin).okData();
        assertThat(child.path("source").asText()).isEqualTo("INSPECTION");
        assertThat(jdbc.queryForMap("SELECT source_ref, cabinet_no, site_no FROM wo_order WHERE wo_no=?", child.path("woNo").asText()))
                .containsEntry("cabinet_no", cab).containsEntry("site_no", site)
                .hasEntrySatisfying("source_ref", v -> assertThat((String) v).startsWith(insp + ":"));
        assertThat(post("/api/ops/work-orders/" + insp + "/derive", Map.of("type", "FAULT", "description", "再报一次"), admin).okData()
                .path("woNo").asText()).isEqualTo(child.path("woNo").asText());
        assertThat(post("/api/ops/work-orders/" + insp + "/derive", Map.of("type", "INSTALL", "description", "x"), admin).status).isEqualTo(400);

        String fault = workOrder("FAULT", "PROCESSING", cab, site);
        assertThat(post("/api/ops/work-orders/" + fault + "/derive", Map.of("type", "FAULT", "description", "x"), admin).status).isEqualTo(409);
        String fresh = workOrder("INSPECT", "CREATED", cab, site);
        assertThat(post("/api/ops/work-orders/" + fresh + "/derive", Map.of("type", "FAULT", "description", "x"), admin).status).isEqualTo(409);
    }

    @Test
    @DisplayName("D5 巡检清点：现场 5 颗、系统在柜 3 颗 → 一条巡检来源的数量差异；柜子不动")
    void inspectionCount() {
        String site = site("R-D" + rnd());
        String cab = cabinet(site, 8, 3);
        powerbanks(cab, 3, 90, false);
        String insp = workOrder("INSPECT", "PROCESSING", cab, site);
        post("/api/ops/work-orders/" + insp + "/complete", Map.of("handleNote", "例行巡检", "countedQty", 5), admin).okData();
        assertThat(jdbc.queryForMap("SELECT kind, expected_qty, actual_qty FROM inv_asset_diff WHERE source_type='INSPECTION' AND source_ref=?", insp))
                .containsEntry("kind", "COUNT_MISMATCH").containsEntry("expected_qty", 3).containsEntry("actual_qty", 5);
        assertThat(jdbc.queryForObject("SELECT status FROM dev_cabinet WHERE cabinet_no=?", String.class, cab)).isEqualTo("DEPLOYED");
    }

    // —— 夹具 ——

    private String site(String region) {
        String no = "STE" + rnd();
        siteNos.add(no);
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours, region_id) VALUES (?, 'MAIN', ?, ?, 'ACTIVE', '00:00-23:59', ?)",
                no, venue, "批次D测试站点 " + no, region);
        return no;
    }

    private String cabinet(String site, int slots, int available) {
        String cab = "CBE" + rnd();
        cabinets.add(cab);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, site_no, slot_total, available_count, status,"
                + " online_status, last_heartbeat_at) VALUES (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, ?, ?, 'DEPLOYED', 'ONLINE', NOW(3))",
                cab, cab, site, slots, available);
        return cab;
    }

    private void powerbanks(String cab, int n, int battery, boolean aged) {
        for (int i = 0; i < n; i++) {
            String pb = powerbank(cab, null, battery, 10);
            if (aged) jdbc.update("UPDATE dev_powerbank SET health='AGED' WHERE powerbank_no=?", pb);
        }
    }

    private String powerbank(String cab, Integer slot, int battery, int cycles) {
        String pb = "PBE" + rnd();
        jdbc.update("INSERT INTO dev_powerbank (powerbank_no, tenant_id, sn, battery, cycles, status, cabinet_no, slot_index) VALUES (?, 'MAIN', ?, ?, ?, 'IN_CABINET', ?, ?)",
                pb, pb, battery, cycles, cab, slot);
        return pb;
    }

    private String workOrder(String type, String status, String cab, String site) {
        String no = "WOE" + rnd();
        jdbc.update("INSERT INTO wo_order (wo_no, tenant_id, type, source, status, cabinet_no, site_no) VALUES (?, 'MAIN', ?, 'PLAN', ?, ?, ?)",
                no, type, status, cab, site);
        return no;
    }

    private static String rnd() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
