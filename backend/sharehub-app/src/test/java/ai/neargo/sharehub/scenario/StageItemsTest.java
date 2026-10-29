package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 阶段下钻（P2 #6）：每个阶段的清单总数 = 总览台数（同一份筛选条件）；在仓的柜带仓号与型号。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StageItemsTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private final String cab = "CABSI" + Long.toString(System.nanoTime(), 36).toUpperCase();

    @AfterAll
    void cleanup() {
        ai.neargo.sharehub.support.FixtureCleanup.dropCabinets(jdbc, List.of(cab));
    }

    @Test
    @DisplayName("★ 各阶段 × 类型：下钻 total = 总览台数；新进仓的柜出现在 IN_STOCK 清单里")
    void totalsMatchStages() {
        String token = login("ADMIN");
        post("/api/ops/cabinets", Map.of("cabinetNo", cab, "sn", cab, "vendorCode", "TEST", "slotTotal", 8), token).okData();
        jdbc.update("UPDATE dev_cabinet SET warehouse_no = 'WH002' WHERE cabinet_no = ?", cab);

        for (JsonNode s : get("/api/ops/inventory/stages", token).okData()) {
            String stage = s.path("stage").asText();
            assertThat(get("/api/ops/inventory/stage-items?stage=" + stage + "&itemType=CABINET", token).okData().path("total").asLong())
                    .as(stage + " 柜").isEqualTo(s.path("cabinets").asLong());
            assertThat(get("/api/ops/inventory/stage-items?stage=" + stage + "&itemType=POWERBANK", token).okData().path("total").asLong())
                    .as(stage + " 宝").isEqualTo(s.path("powerbanks").asLong());
        }
        JsonNode items = get("/api/ops/inventory/stage-items?stage=IN_STOCK&itemType=CABINET", token).okData().path("items");
        JsonNode mine = null;
        for (JsonNode i : items) if (cab.equals(i.path("itemNo").asText())) mine = i;
        assertThat(mine).isNotNull();
        assertThat(mine.path("warehouseNo").asText()).isEqualTo("WH002");

        assertThat(get("/api/ops/inventory/stage-items?stage=NOPE", token).status).isEqualTo(400);
        assertThat(get("/api/ops/inventory/stage-items?stage=IN_STOCK&itemType=X", token).status).isEqualTo(400);
    }
}
