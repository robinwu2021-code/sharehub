package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 库存报表（V206）：仓 × 类型行、期间采购金额、处置汇总；权限 report:inventory:read（仓管有、客服没有）。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryReportTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
    private final List<String> cabinets = new ArrayList<>();
    private final List<String> intakes = new ArrayList<>();
    private final List<String> disposals = new ArrayList<>();
    private String wh;

    @AfterAll
    void cleanup() {
        for (String no : intakes) {
            jdbc.update("DELETE FROM inv_intake_item WHERE intake_no=?", no);
            jdbc.update("DELETE FROM inv_intake WHERE intake_no=?", no);
        }
        for (String no : disposals) {
            jdbc.update("DELETE FROM inv_disposal_item WHERE disposal_no=?", no);
            jdbc.update("DELETE FROM inv_disposal WHERE disposal_no=?", no);
        }
        ai.neargo.sharehub.support.FixtureCleanup.dropCabinets(jdbc, cabinets);
        if (wh != null) jdbc.update("DELETE FROM inv_warehouse WHERE warehouse_no=?", wh);
    }

    private String cabinet(String token) {
        String no = "CABRP" + tag + cabinets.size();
        post("/api/ops/cabinets", Map.of("cabinetNo", no, "sn", no, "vendorCode", "TEST", "slotTotal", 8), token).okData();
        jdbc.update("UPDATE dev_cabinet SET warehouse_no = NULL WHERE cabinet_no = ?", no);
        cabinets.add(no);
        return no;
    }

    @Test
    @DisplayName("期初建账两台带单价 + 报废一台 → 报表行、采购金额、处置汇总都对得上")
    void report() {
        String token = login("ADMIN");
        wh = post("/api/ops/inventory/warehouses", Map.of("name", "报表仓-" + tag), token).okData().path("warehouseNo").asText();
        String a = cabinet(token), b = cabinet(token);
        intakes.add(post("/api/ops/inventory/intakes", Map.of("kind", "OPENING", "warehouseNo", wh, "currency", "AED",
                "unitCosts", Map.of(a, 1000, b, 500), "cabinetNos", List.of(a, b)), token).okData().path("intake").path("intakeNo").asText());
        disposals.add(post("/api/ops/inventory/disposals", Map.of("kind", "SCRAP", "reasonCode", "BROKEN", "warehouseNo", wh, "note", "坏",
                "cabinetNos", List.of(a)), token).okData().path("disposal").path("disposalNo").asText());

        String today = LocalDate.now().toString();
        JsonNode r = get("/api/ops/reports/inventory?from=" + today + "&to=" + today + "&warehouseNo=" + wh, token).okData();
        assertThat(r.path("stages").isArray()).isTrue();
        JsonNode cab = null;
        for (JsonNode row : r.path("rows")) if ("CABINET".equals(row.path("itemType").asText())) cab = row;
        assertThat(cab).isNotNull();
        assertThat(cab.path("available").asInt()).isEqualTo(1);
        assertThat(r.path("disposals").path("SCRAP:BROKEN").asInt()).isEqualTo(1);
        assertThat(r.path("purchases").get(0).path("currency").asText()).isEqualTo("AED");
        assertThat(r.path("purchases").get(0).path("amount").decimalValue()).isEqualByComparingTo("1500");

        assertThat(get("/api/ops/reports/inventory", login("WAREHOUSE")).status).isEqualTo(200);
        assertThat(get("/api/ops/reports/inventory", login("CS")).status).isEqualTo(403);
        assertThat(get("/api/ops/reports/inventory?from=2026-05-01&to=2026-01-01", token).status).isEqualTo(400);
    }
}
