package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.inv.remote.HttpInventoryApi;
import ai.neargo.sharehub.inv.remote.RemoteInventoryPort;
import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.InventoryFake;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 库存流水与盘点（S4）：本地引擎流水取进出仓履历；盘点只在独立进销存引擎下可用，执行须 device:inventory:stocktake。 */
class StockViewTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RemoteInventoryPort remote;
    @Autowired
    HttpInventoryApi http;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();

    /** 新柜经到仓待检入库到 WH003。 */
    private String stocked() {
        String c = "CABV" + tag;
        post("/api/ops/cabinets", Map.of("cabinetNo", c, "sn", c, "vendorCode", "TEST", "slotTotal", 8), login("ADMIN")).okData();
        jdbc.update("UPDATE dev_cabinet SET warehouse_no = NULL WHERE cabinet_no = ?", c);
        String wh = login("WAREHOUSE");
        String no = post("/api/ops/inventory/intakes", Map.of("warehouseNo", "WH003", "cabinetNos", List.of(c)), wh)
                .okData().path("intake").path("intakeNo").asText();
        post("/api/ops/inventory/intakes/" + no + "/items/CABINET/" + c + "/qc", Map.of("powerOn", true, "slotsOk", true), wh).okData();
        post("/api/ops/inventory/intakes/" + no + "/inbound", Map.of("items", List.of(Map.of("itemType", "CABINET", "itemNo", c))), wh).okData();
        return c;
    }

    @Test
    @DisplayName("★ 本地引擎：入库在流水里是该仓 +1；盘点 409（本地没有盘点账）")
    void local_ledger_and_no_counts() {
        String c = stocked();
        String admin = login("ADMIN");
        assertThat(get("/api/ops/inventory/engine", admin).okData().path("engine").asText()).isEqualTo("LOCAL");
        JsonNode rows = get("/api/ops/inventory/ledger?warehouseNo=WH003&keyword=" + c, admin).okData().path("list");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("qtyDelta").asInt()).isEqualTo(1);
        assertThat(rows.get(0).path("docKind").asText()).isEqualTo("INBOUND");
        assertThat(post("/api/ops/inventory/counts", Map.of("warehouseNo", "WH003"), admin).status).isEqualTo(409);
    }

    @Test
    @DisplayName("★ 独立进销存引擎：开盘点 → 扫码 → 提交 → 复核 → 过账；只读角色不能开单")
    void service_count_flow() {
        InventoryFake fake = new InventoryFake();
        remote.useApi(fake);
        jdbc.update("UPDATE sys_param SET value='SERVICE' WHERE param_key='inv.engine'");
        jdbc.update("DELETE FROM inventory_sync_state");
        try {
            String c = stocked();
            String wh = login("WAREHOUSE");
            assertThat(post("/api/ops/inventory/counts", Map.of("warehouseNo", "WH003"), login("VIEWER")).status).isEqualTo(403);
            JsonNode d = post("/api/ops/inventory/counts", Map.of("warehouseNo", "WH003"), wh).okData();
            String no = d.path("count").path("countNo").asText();
            assertThat(d.path("count").path("warehouseNo").asText()).isEqualTo("WH003");
            assertThat(d.path("missingSerials").toString()).contains(c);

            d = post("/api/ops/inventory/counts/" + no + "/scans", Map.of("serialNos", List.of(c)), wh).okData();
            assertThat(d.path("missingSerials")).isEmpty();
            assertThat(d.path("lines").get(0).path("itemType").asText()).isEqualTo("CABINET");
            post("/api/ops/inventory/counts/" + no + "/submit", Map.of(), wh).okData();
            post("/api/ops/inventory/counts/" + no + "/approve", Map.of(), wh).okData();
            JsonNode posted = post("/api/ops/inventory/counts/" + no + "/post", Map.of(), wh).okData();
            assertThat(posted.path("count").path("status").asText()).isEqualTo("POSTED");
            assertThat(get("/api/ops/inventory/counts", wh).okData().toString()).contains(no);
            assertThat(get("/api/ops/inventory/ledger?warehouseNo=WH003", wh).okData().path("total").asInt()).isPositive();
        } finally {
            jdbc.update("UPDATE sys_param SET value='LOCAL' WHERE param_key='inv.engine'");
            jdbc.update("DELETE FROM inventory_sync_state");
            remote.useApi(http);
        }
    }
}
