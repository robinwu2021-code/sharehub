package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.alarm.engine.AlarmEngine;
import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 库存流程卡点告警（V197，A2 / A3）：待检超期 → 仓管待办；撤机完工去向回仓却没回仓单 → 运营待办。
 * 处理完即恢复关闭。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryAlarmTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AlarmEngine engine;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
    private final List<String> subjects = new ArrayList<>();

    @AfterAll
    void cleanup() {
        for (String s : subjects) {
            jdbc.update("DELETE FROM dev_alarm WHERE subject_no=? AND alarm_code IN ('INTAKE_OVERDUE','REMOVAL_RETURN_MISSING')", s);
            jdbc.update("DELETE FROM inv_intake_item WHERE intake_no=?", s);
            jdbc.update("DELETE FROM inv_intake WHERE intake_no=?", s);
            jdbc.update("DELETE FROM inv_transfer WHERE source_type='REMOVAL' AND source_ref=?", s);
            jdbc.update("DELETE FROM wo_order WHERE wo_no=?", s);
        }
    }

    /** 恢复与关闭分属引擎相邻两轮（生产每分钟一轮）：先标恢复，下一轮关闭。 */
    private void recoverTicks() {
        LocalDateTime now = LocalDateTime.now();
        engine.tick(now);
        engine.tick(now.plusMinutes(1));
    }

    private String status(String code, String subjectNo) {
        List<String> r = jdbc.queryForList("SELECT status FROM dev_alarm WHERE alarm_code=? AND subject_no=? ORDER BY id DESC LIMIT 1",
                String.class, code, subjectNo);
        return r.isEmpty() ? null : r.get(0);
    }

    @Test
    @DisplayName("★ A2 待检超期：到达超过 7 天未处理 → 开告警（待办给仓管）；处理完 → 关闭")
    void intake_overdue_opens_then_closes() {
        String c = "CABIA" + tag;
        post("/api/ops/cabinets", Map.of("cabinetNo", c, "sn", c, "vendorCode", "TEST", "slotTotal", 8), login("ADMIN")).okData();
        jdbc.update("UPDATE dev_cabinet SET warehouse_no = NULL WHERE cabinet_no = ?", c);
        String token = login("WAREHOUSE");
        String no = post("/api/ops/inventory/intakes", Map.of("warehouseNo", "WH001", "cabinetNos", List.of(c)), token)
                .okData().path("intake").path("intakeNo").asText();
        subjects.add(no);
        engine.tick(LocalDateTime.now());
        assertThat(status("INTAKE_OVERDUE", no)).as("刚到不报").isNull();

        jdbc.update("UPDATE inv_intake SET arrived_at = ? WHERE intake_no = ?", LocalDateTime.now().minusDays(8), no);
        engine.tick(LocalDateTime.now());
        assertThat(status("INTAKE_OVERDUE", no)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT subject_type FROM dev_alarm WHERE alarm_code='INTAKE_OVERDUE' AND subject_no=?", String.class, no))
                .isEqualTo("INTAKE");

        post("/api/ops/inventory/intakes/" + no + "/items/CABINET/" + c + "/qc", Map.of("powerOn", true, "slotsOk", true), token).okData();
        post("/api/ops/inventory/intakes/" + no + "/inbound", Map.of("items", List.of(Map.of("itemType", "CABINET", "itemNo", c))), token).okData();
        recoverTicks();
        assertThat(status("INTAKE_OVERDUE", no)).as("单据处理完自动关闭").isNotEqualTo("OPEN");
        ai.neargo.sharehub.support.FixtureCleanup.dropCabinets(jdbc, List.of(c));
    }

    @Test
    @DisplayName("★ A3 撤机完工去向回仓、过了宽限仍没回仓单 → 开告警（待办给运营）；补上回仓单 → 关闭；回出库池的不报")
    void removal_without_return_transfer_opens_then_closes() {
        String wo = "WOIA" + tag;
        String pool = "WOIP" + tag;
        subjects.add(wo);
        subjects.add(pool);
        jdbc.update("INSERT INTO wo_order (wo_no, tenant_id, type, source, status, removal_disposition, cabinet_no, updated_at)"
                + " VALUES (?, 'MAIN', 'REMOVE', 'MANUAL', 'DONE', 'RETURN_TO_WAREHOUSE', 'CAB-IA', ?)", wo, LocalDateTime.now().minusHours(2));
        jdbc.update("INSERT INTO wo_order (wo_no, tenant_id, type, source, status, removal_disposition, cabinet_no, updated_at)"
                + " VALUES (?, 'MAIN', 'REMOVE', 'MANUAL', 'DONE', 'RETURN_TO_POOL', 'CAB-IP', ?)", pool, LocalDateTime.now().minusHours(2));
        engine.tick(LocalDateTime.now());
        assertThat(status("REMOVAL_RETURN_MISSING", wo)).isEqualTo("OPEN");
        assertThat(status("REMOVAL_RETURN_MISSING", pool)).as("回出库池不需要回仓单").isNull();

        jdbc.update("INSERT INTO inv_transfer (transfer_no, tenant_id, from_type, from_ref, to_type, to_ref, item_type, powerbank_count,"
                + " status, operator_no, source_type, source_ref) VALUES (?, 'MAIN', 'SITE', 'ST-IA', 'WAREHOUSE', 'WH001', 'CABINET', 1,"
                + " 'DRAFT', 'test', 'REMOVAL', ?)", "TRIA" + tag, wo);
        recoverTicks();
        assertThat(status("REMOVAL_RETURN_MISSING", wo)).as("补上回仓单自动关闭").isNotEqualTo("OPEN");
    }
}
