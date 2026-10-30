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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 入库单（V202）：期初建账登记即入库；采购到货带单价，单头汇总金额。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntakeReceiptTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
    private int seq;
    private final List<String> cabinets = new ArrayList<>();
    private final List<String> intakes = new ArrayList<>();

    @AfterAll
    void cleanup() {
        for (String no : intakes) {
            jdbc.update("DELETE FROM inv_intake_item WHERE intake_no=?", no);
            jdbc.update("DELETE FROM inv_intake WHERE intake_no=?", no);
        }
        ai.neargo.sharehub.support.FixtureCleanup.dropCabinets(jdbc, cabinets);
    }

    private String newCabinet(String token) {
        String no = "CABR" + tag + (++seq);
        post("/api/ops/cabinets", Map.of("cabinetNo", no, "sn", no, "vendorCode", "TEST", "slotTotal", 8), token).okData();
        jdbc.update("UPDATE dev_cabinet SET warehouse_no = NULL WHERE cabinet_no = ?", no);
        cabinets.add(no);
        return no;
    }

    @Test
    @DisplayName("期初建账：登记即入库、单据直接完成，设备落到该仓")
    void opening() {
        String token = login("ADMIN");
        String c = newCabinet(token);
        JsonNode d = post("/api/ops/inventory/intakes", Map.of("kind", "OPENING", "warehouseNo", "WH002", "cabinetNos", List.of(c), "openingBasis", "期初盘点表 2026-10"), token).okData();
        intakes.add(d.path("intake").path("intakeNo").asText());
        assertThat(d.path("intake").path("kind").asText()).isEqualTo("OPENING");
        assertThat(d.path("intake").path("status").asText()).isEqualTo("DONE");
        assertThat(d.path("intake").path("inbound").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT warehouse_no FROM dev_cabinet WHERE cabinet_no=?", String.class, c)).isEqualTo("WH002");
    }

    @Test
    @DisplayName("采购到货带单价：单头合计；有单价无币种 / 负单价 / 非法 kind 被拒")
    void costs() {
        String token = login("ADMIN");
        String a = newCabinet(token), b = newCabinet(token);
        JsonNode d = post("/api/ops/inventory/intakes", Map.of("warehouseNo", "WH002", "currency", "aed",
                "unitCosts", Map.of(a, 1200.5, b, 800), "cabinetNos", List.of(a, b)), token).okData();
        intakes.add(d.path("intake").path("intakeNo").asText());
        assertThat(d.path("intake").path("kind").asText()).isEqualTo("ARRIVAL");
        assertThat(d.path("intake").path("currency").asText()).isEqualTo("AED");
        assertThat(d.path("intake").path("totalCost").decimalValue()).isEqualByComparingTo("2000.50");
        assertThat(d.path("intake").path("status").asText()).isEqualTo("OPEN");

        String c = newCabinet(token);
        assertThat(post("/api/ops/inventory/intakes", Map.of("warehouseNo", "WH002", "unitCosts", Map.of(c, 1), "cabinetNos", List.of(c)), token).status).isEqualTo(400);
        assertThat(post("/api/ops/inventory/intakes", Map.of("warehouseNo", "WH002", "currency", "AED", "unitCosts", Map.of(c, -1), "cabinetNos", List.of(c)), token).status).isEqualTo(400);
        assertThat(post("/api/ops/inventory/intakes", Map.of("kind", "RETURN", "warehouseNo", "WH002", "cabinetNos", List.of(c)), token).status).isEqualTo(400);
    }
}
