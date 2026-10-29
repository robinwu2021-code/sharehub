package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.alarm.engine.AlarmEngine;
import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
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

/** 安全库存（V204）：设下限 → 可用量低于下限开 LOW_STOCK 待办 → 补货到下限自动关闭。用测试专建的仓，不受别的用例影响。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SafetyStockTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AlarmEngine engine;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
    private final List<String> cabinets = new ArrayList<>();
    private String wh;

    @AfterAll
    void cleanup() {
        if (wh != null) {
            jdbc.update("DELETE FROM dev_alarm WHERE alarm_code='LOW_STOCK' AND subject_no LIKE ?", wh + ":%");
            jdbc.update("DELETE FROM inv_stock_threshold WHERE warehouse_no=?", wh);
        }
        ai.neargo.sharehub.support.FixtureCleanup.dropCabinets(jdbc, cabinets);
        if (wh != null) jdbc.update("DELETE FROM inv_warehouse WHERE warehouse_no=?", wh);
    }

    private String status(String subject) {
        List<String> r = jdbc.queryForList("SELECT status FROM dev_alarm WHERE alarm_code='LOW_STOCK' AND subject_no=? ORDER BY id DESC LIMIT 1",
                String.class, subject);
        return r.isEmpty() ? null : r.get(0);
    }

    @Test
    @DisplayName("★ 低于安全库存开告警（对象 WAREHOUSE）；补货后关闭；下限 0 不报；仓管不能改下限")
    void lowStockOpensThenCloses() {
        String token = login("ADMIN");
        wh = post("/api/ops/inventory/warehouses", Map.of("name", "安全库存仓-" + tag), token).okData().path("warehouseNo").asText();
        JsonNode rows = get("/api/ops/inventory/stock-thresholds?warehouseNo=" + wh, token).okData();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).path("minQty").asInt()).isZero();

        assertThat(post("/api/ops/inventory/stock-thresholds", Map.of("warehouseNo", wh, "itemType", "CABINET", "minQty", -1), token).status).isEqualTo(400);
        assertThat(post("/api/ops/inventory/stock-thresholds", Map.of("warehouseNo", wh, "itemType", "CABINET", "minQty", 1), login("WAREHOUSE")).status)
                .isEqualTo(403);
        JsonNode t = post("/api/ops/inventory/stock-thresholds", Map.of("warehouseNo", wh, "itemType", "CABINET", "minQty", 1), token).okData();
        assertThat(t.path("low").asBoolean()).isTrue();

        String subject = wh + ":CABINET";
        engine.tick(LocalDateTime.now());
        assertThat(status(subject)).isEqualTo("OPEN");
        assertThat(status(wh + ":POWERBANK")).as("下限 0 不报").isNull();
        assertThat(jdbc.queryForObject("SELECT subject_type FROM dev_alarm WHERE alarm_code='LOW_STOCK' AND subject_no=? ORDER BY id DESC LIMIT 1",
                String.class, subject)).isEqualTo("WAREHOUSE");

        String c = "CABSS" + tag;
        post("/api/ops/cabinets", Map.of("cabinetNo", c, "sn", c, "vendorCode", "TEST", "slotTotal", 8), token).okData();
        cabinets.add(c);
        jdbc.update("UPDATE dev_cabinet SET warehouse_no = ? WHERE cabinet_no = ?", wh, c);
        LocalDateTime now = LocalDateTime.now();
        engine.tick(now);
        engine.tick(now.plusMinutes(1));
        assertThat(status(subject)).as("补到下限自动关闭").isNotEqualTo("OPEN");
    }
}
