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
    private int seq;

    /** 新柜经到仓待检入库到 WH003。 */
    private String stocked() {
        String c = "CABV" + tag + (++seq);
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
    @DisplayName("★ S6 统一视图与期间报表（本地引擎）：入库后在仓 +1；当天期间报表该仓入 ≥ 1、期初 + 入 − 出 = 期末")
    void stages_and_period_local() {
        String admin = login("ADMIN");
        JsonNode before = get("/api/ops/inventory/stages", admin).okData();
        long inStock = 0;
        for (JsonNode r : before) if ("IN_STOCK".equals(r.path("stage").asText())) inStock = r.path("cabinets").asLong();
        stocked();
        JsonNode after = get("/api/ops/inventory/stages", admin).okData();
        long inStock2 = 0;
        for (JsonNode r : after) if ("IN_STOCK".equals(r.path("stage").asText())) inStock2 = r.path("cabinets").asLong();
        assertThat(inStock2).isEqualTo(inStock + 1);

        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        JsonNode rows = get("/api/ops/inventory/period-summary?from=" + today + "&to=" + today + "&warehouseNo=WH003", admin).okData();
        JsonNode cab = null;
        for (JsonNode r : rows) if ("CABINET".equals(r.path("itemType").asText())) cab = r;
        assertThat(cab).isNotNull();
        assertThat(cab.path("inQty").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(cab.path("opening").asLong() + cab.path("inQty").asLong() - cab.path("outQty").asLong())
                .isEqualTo(cab.path("closing").asLong());
    }

    @Test
    @DisplayName("★ S7 存量迁移（对 Fake）：在仓的入库、出库池的入库 + 出库 + 领取；重跑不重复；只有系统参数权限能执行")
    void migration_against_fake() {
        InventoryFake fake = new InventoryFake();
        remote.useApi(fake);
        jdbc.update("DELETE FROM inventory_sync_state");
        String out = null;
        try {
            String inWh = stocked();
            String admin = login("ADMIN");
            out = stocked();
            String agent = jdbc.queryForObject("SELECT issued_agent_no FROM dev_cabinet WHERE issued_agent_no IS NOT NULL LIMIT 1", String.class);
            jdbc.update("UPDATE dev_cabinet SET warehouse_no=NULL, issued_agent_no=?, issued_at=NOW(3), issue_claimed_at=NOW(3) WHERE cabinet_no=?",
                    agent, out);
            assertThat(post("/api/ops/inventory/migration", Map.of("limit", 5), login("WAREHOUSE")).status).isEqualTo(403);
            JsonNode plan = get("/api/ops/inventory/migration", admin).okData();
            assertThat(plan.path("toInbound").asInt()).isPositive();
            assertThat(plan.path("toIssue").asInt()).isPositive();

            // 只迁这两台：把别的候选先标成已完成，避免把共享测试库里的存量全推一遍
            jdbc.update("INSERT INTO inventory_sync_state (k, v) SELECT CONCAT('mig:CABINET:', cabinet_no), 'OUT' FROM dev_cabinet "
                    + "WHERE cabinet_no NOT IN (?, ?) ON DUPLICATE KEY UPDATE v = VALUES(v)", inWh, out);
            jdbc.update("INSERT INTO inventory_sync_state (k, v) SELECT CONCAT('mig:POWERBANK:', powerbank_no), 'IN' FROM dev_powerbank "
                    + "ON DUPLICATE KEY UPDATE v = VALUES(v)");
            // 共享测试库里别的用例留下的未完成操作会挡住迁移（F08），本段期间暂挂
            jdbc.update("UPDATE inv_operation SET tenant_id='PARKED-MIG' WHERE tenant_id='MAIN' AND phase NOT IN ('COMPLETED','REJECTED')");
            // 第一轮：已签收的那台领取生效但响应丢了 → 这台记失败
            fake.loseNextClaimResponse = true;
            JsonNode r = post("/api/ops/inventory/migration", Map.of("limit", 50), admin).okData();
            assertThat(r.path("processed").asInt()).as("%s", r).isEqualTo(1);
            assertThat(r.path("failed").asInt()).isEqualTo(1);
            // 重跑：固定键回放入库 / 出库，领取按原请求号查回 —— 不再建单、不重复领取
            JsonNode again = post("/api/ops/inventory/migration", Map.of("limit", 50), admin).okData();
            assertThat(again.path("failed").asInt()).as("%s", again).isZero();
            assertThat(again.path("processed").asInt()).isEqualTo(1);
            assertThat(fake.stock).containsKey(inWh);
            assertThat(fake.dispatchesBySerial(out)).anyMatch(v -> "CLAIMED".equals(v.status()) && agent.equals(v.rootTargetRef()));
            assertThat(fake.claims).as("只领了一次").hasSize(1);
            String outNo = out;
            assertThat(fake.docs.values().stream().filter(d -> "OUT".equals(d.kind()) && d.serials().contains(outNo)).count()).isEqualTo(1);
            assertThat(post("/api/ops/inventory/migration", Map.of("limit", 50), admin).okData().path("processed").asInt())
                    .as("重跑不重复").isZero();
        } finally {
            jdbc.update("DELETE FROM inventory_sync_state");
            jdbc.update("UPDATE inv_operation SET tenant_id='MAIN' WHERE tenant_id='PARKED-MIG'");
            remote.useApi(http);
            // 借用的商家出库池不能留下这台无点位的柜：别的用例（数据范围）按「商家可见的柜必有点位」断言
            if (out != null) {
                jdbc.update("UPDATE dev_cabinet SET issued_agent_no=NULL, issued_at=NULL, issue_claimed_at=NULL, warehouse_no='WH003', "
                        + "archived_at=NOW(3) WHERE cabinet_no=?", out);
            }
        }
    }

    @Test
    @DisplayName("★ 独立进销存引擎：开盘点／扫码；未配置统一审批时拒绝提交及直接复核过账")
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
            assertThat(post("/api/ops/inventory/counts/" + no + "/submit", Map.of(), wh).status).isEqualTo(409);
            assertThat(post("/api/ops/inventory/counts/" + no + "/approve", Map.of(), wh).status).isEqualTo(409);
            assertThat(post("/api/ops/inventory/counts/" + no + "/post", Map.of(), wh).status).isEqualTo(409);
            assertThat(fake.count(no).status()).isEqualTo("OPEN");
            assertThat(get("/api/ops/inventory/counts", wh).okData().toString()).contains(no);
            assertThat(get("/api/ops/inventory/ledger?warehouseNo=WH003", wh).okData().path("total").asInt()).isPositive();
        } finally {
            jdbc.update("UPDATE sys_param SET value='LOCAL' WHERE param_key='inv.engine'");
            jdbc.update("DELETE FROM inventory_sync_state");
            remote.useApi(http);
        }
    }
}
