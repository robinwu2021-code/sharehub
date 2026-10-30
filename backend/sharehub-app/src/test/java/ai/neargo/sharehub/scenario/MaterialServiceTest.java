package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.inv.remote.HttpInventoryApi;
import ai.neargo.sharehub.inv.remote.RemoteInventoryPort;
import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.InventoryFake;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 库存管理 · 物料（V210）：物料档案是宿主主数据，账记在库存服务（这里用内存 Fake）。
 * 覆盖 M1–M5 与 LOCAL 引擎拒绝、处置出库在 SERVICE 引擎下同步库存服务。
 */
class MaterialServiceTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RemoteInventoryPort remote;
    @Autowired
    HttpInventoryApi http;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
    private InventoryFake fake;

    @BeforeEach
    void serviceEngine() {
        fake = new InventoryFake();
        remote.useApi(fake);
        jdbc.update("UPDATE sys_param SET value='SERVICE' WHERE param_key='inv.engine'");
        jdbc.update("DELETE FROM inventory_sync_state");
    }

    @AfterEach
    void localEngine() {
        jdbc.update("UPDATE sys_param SET value='LOCAL' WHERE param_key='inv.engine'");
        jdbc.update("DELETE FROM inventory_sync_state");
        remote.useApi(http);
        jdbc.update("DELETE FROM inv_material_doc_line WHERE doc_no IN (SELECT doc_no FROM inv_material_doc WHERE note LIKE ?)", "%" + tag + "%");
        jdbc.update("DELETE FROM inv_material_doc WHERE note LIKE ?", "%" + tag + "%");
        jdbc.update("DELETE FROM inv_material_threshold WHERE material_no IN (SELECT material_no FROM inv_material WHERE name LIKE ?)", "%" + tag);
        jdbc.update("DELETE FROM inv_material WHERE name LIKE ?", "%" + tag);
    }

    private String material(String token, String name, String tracking) {
        return post("/api/ops/inventory/materials", Map.of("name", name + tag, "category", "SPARE_PART", "trackingMode", tracking, "uom", "个"), token)
                .okData().path("materialNo").asText();
    }

    private Resp doc(String token, Map<String, Object> body) {
        Map<String, Object> b = new HashMap<>(body);
        b.putIfAbsent("note", "测试 " + tag);
        return post("/api/ops/inventory/material-docs", b, token);
    }

    private int onHand(String token, String wh, String mat) {
        JsonNode rows = get("/api/ops/inventory/material-balances?warehouseNo=" + wh + "&materialNo=" + mat, token).okData();
        return rows.isEmpty() ? 0 : rows.get(0).path("onHand").asInt();
    }

    @Test
    @DisplayName("★ 采购入库 → 领用（不够一次列全）→ 余额以库存服务为准；有流水后跟踪方式锁定、有库存不能归档")
    void quantityMaterial() {
        String token = login("ADMIN");
        String lock = material(token, "锁舌", "QUANTITY");
        String sp = post("/api/ops/inventory/suppliers", Map.of("name", "物料供应商" + tag), token).okData().path("supplierNo").asText();
        assertThat(doc(token, Map.of("purpose", "PURCHASE", "warehouseNo", "WH002", "lines", List.of(Map.of("materialNo", lock, "qty", 5)))).status)
                .as("采购须供应商").isEqualTo(400);
        JsonNode in = doc(token, Map.of("purpose", "PURCHASE", "warehouseNo", "WH002", "supplierNo", sp, "currency", "aed",
                "lines", List.of(Map.of("materialNo", lock, "qty", 10, "unitCost", 3.5)))).okData();
        assertThat(in.path("kind").asText()).isEqualTo("INBOUND");
        assertThat(in.path("currency").asText()).isEqualTo("AED");
        assertThat(onHand(token, "WH002", lock)).isEqualTo(10);

        Resp shortage = doc(token, Map.of("purpose", "REQUISITION", "warehouseNo", "WH002", "woNo", "WO-" + tag,
                "lines", List.of(Map.of("materialNo", lock, "qty", 11))));
        assertThat(shortage.status).isEqualTo(409);
        assertThat(shortage.body.toString()).contains("不够出 11");
        assertThat(doc(token, Map.of("purpose", "REQUISITION", "warehouseNo", "WH002", "lines", List.of(Map.of("materialNo", lock, "qty", 1)))).status)
                .as("领用须领用人或工单").isEqualTo(400);
        doc(token, Map.of("purpose", "REQUISITION", "warehouseNo", "WH002", "woNo", "WO-" + tag, "lines", List.of(Map.of("materialNo", lock, "qty", 4)))).okData();
        assertThat(onHand(token, "WH002", lock)).isEqualTo(6);

        assertThat(post("/api/ops/inventory/materials/" + lock, Map.of("name", "锁舌" + tag, "category", "SPARE_PART", "trackingMode", "SERIAL", "uom", "个"), token).status)
                .as("有流水后跟踪方式不可改").isEqualTo(409);
        assertThat(post("/api/ops/inventory/materials/" + lock + "/archive", Map.of(), token).status).as("有库存不能归档").isEqualTo(409);

        post("/api/ops/inventory/material-thresholds", Map.of("warehouseNo", "WH002", "materialNo", lock, "minQty", 8), token).okData();
        JsonNode low = get("/api/ops/inventory/material-balances?lowOnly=true&materialNo=" + lock, token).okData();
        assertThat(low).hasSize(1);
        assertThat(low.get(0).path("low").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("逐件物料：期初入库 → 调拨（库存服务一出一入）→ 序列号跟着走；报废须原因、退供应商带 SUPPLIER 去向")
    void serialMaterial() {
        String token = login("ADMIN");
        String pos = material(token, "POS", "SERIAL");
        doc(token, Map.of("purpose", "OPENING", "warehouseNo", "WH002",
                "lines", List.of(Map.of("materialNo", pos, "serialNos", List.of("SN1" + tag, "SN2" + tag, "SN3" + tag))))).okData();
        assertThat(doc(token, Map.of("purpose", "TRANSFER", "warehouseNo", "WH002", "toWarehouseNo", "WH002",
                "lines", List.of(Map.of("materialNo", pos, "serialNos", List.of("SN1" + tag))))).status).isEqualTo(400);
        JsonNode t = doc(token, Map.of("purpose", "TRANSFER", "warehouseNo", "WH002", "toWarehouseNo", "WH003",
                "lines", List.of(Map.of("materialNo", pos, "serialNos", List.of("SN1" + tag))))).okData();
        assertThat(t.path("kind").asText()).isEqualTo("TRANSFER");
        JsonNode wh3 = get("/api/ops/inventory/material-balances?warehouseNo=WH003&materialNo=" + pos, token).okData();
        assertThat(wh3.get(0).path("serialNos").get(0).asText()).isEqualTo("SN1" + tag);
        assertThat(onHand(token, "WH002", pos)).isEqualTo(2);

        assertThat(doc(token, Map.of("purpose", "SCRAP", "warehouseNo", "WH002", "lines", List.of(Map.of("materialNo", pos, "serialNos", List.of("SN2" + tag))))).status)
                .as("报废须原因").isEqualTo(400);
        Resp notHere = doc(token, Map.of("purpose", "SCRAP", "reasonCode", "BROKEN", "warehouseNo", "WH002",
                "lines", List.of(Map.of("materialNo", pos, "serialNos", List.of("SN1" + tag)))));
        assertThat(notHere.status).isEqualTo(409);
        assertThat(notHere.body.toString()).contains("不在");
        doc(token, Map.of("purpose", "SCRAP", "reasonCode", "BROKEN", "warehouseNo", "WH002",
                "lines", List.of(Map.of("materialNo", pos, "serialNos", List.of("SN2" + tag))))).okData();
        String sp = post("/api/ops/inventory/suppliers", Map.of("name", "退货方" + tag), token).okData().path("supplierNo").asText();
        doc(token, Map.of("purpose", "RETURN_SUPPLIER", "supplierNo", sp, "warehouseNo", "WH002",
                "lines", List.of(Map.of("materialNo", pos, "serialNos", List.of("SN3" + tag))))).okData();
        assertThat(fake.targets).containsKey("SUPPLIER:" + sp);
        assertThat(onHand(token, "WH002", pos)).isZero();
        assertThat(get("/api/ops/inventory/material-docs?materialNo=" + pos, token).okData().path("total").asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("内置物料只读、不进物料单据；LOCAL 引擎下物料单据与物料库存明确拒绝")
    void builtinAndLocal() {
        String token = login("ADMIN");
        assertThat(post("/api/ops/inventory/materials/MT0001/archive", Map.of(), token).status).isEqualTo(409);
        Resp r = doc(token, Map.of("purpose", "OPENING", "warehouseNo", "WH002", "lines", List.of(Map.of("materialNo", "MT0002", "serialNos", List.of("PB" + tag)))));
        assertThat(r.status).isEqualTo(409);
        assertThat(r.body.toString()).contains("设备流程");
        jdbc.update("UPDATE sys_param SET value='LOCAL' WHERE param_key='inv.engine'");
        String m = material(token, "耗材", "QUANTITY");
        assertThat(get("/api/ops/inventory/materials?keyword=" + tag, token).okData().path("total").asInt()).isEqualTo(1);
        assertThat(doc(token, Map.of("purpose", "OPENING", "warehouseNo", "WH002", "lines", List.of(Map.of("materialNo", m, "qty", 1)))).status).isEqualTo(409);
        assertThat(get("/api/ops/inventory/material-balances", token).status).isEqualTo(409);
    }

    @Test
    @DisplayName("权限：仓管能入库、能领用，不能建物料档案；客服都不能")
    void permissions() {
        String m = material(login("ADMIN"), "线材", "QUANTITY");
        String wh = login("WAREHOUSE");
        doc(wh, Map.of("purpose", "OPENING", "warehouseNo", "WH002", "lines", List.of(Map.of("materialNo", m, "qty", 3)))).okData();
        doc(wh, Map.of("purpose", "REQUISITION", "receiverNo", "E1", "warehouseNo", "WH002", "lines", List.of(Map.of("materialNo", m, "qty", 1)))).okData();
        assertThat(post("/api/ops/inventory/materials", Map.of("name", "x" + tag, "category", "CONSUMABLE", "trackingMode", "QUANTITY", "uom", "个"), wh).status)
                .isEqualTo(403);
        assertThat(doc(login("CS"), Map.of("purpose", "OPENING", "warehouseNo", "WH002", "lines", List.of(Map.of("materialNo", m, "qty", 1)))).status)
                .isEqualTo(403);
    }

    @Test
    @DisplayName("★ 处置出库在 SERVICE 引擎下：库存服务做一张 SCRAP 出库（带原因码）；库存服务拒绝则本地整单回滚")
    void disposalUnderServiceEngine() {
        String token = login("ADMIN");
        String m = material(token, "占位", "QUANTITY");
        doc(token, Map.of("purpose", "OPENING", "warehouseNo", "WH002", "lines", List.of(Map.of("materialNo", m, "qty", 1)))).okData();
        String loc = jdbc.queryForObject("SELECT v FROM inventory_sync_state WHERE k = 'wh:WH002'", String.class);
        String a = "CABMS" + tag + "A", b = "CABMS" + tag + "B";
        for (String c : List.of(a, b)) {
            post("/api/ops/cabinets", Map.of("cabinetNo", c, "sn", c, "vendorCode", "TEST", "slotTotal", 8), token).okData();
            jdbc.update("UPDATE dev_cabinet SET warehouse_no = 'WH002' WHERE cabinet_no = ?", c);
        }
        fake.stock.put(a, loc);
        try {
            JsonNode d = post("/api/ops/inventory/disposals", Map.of("kind", "SCRAP", "reasonCode", "BROKEN", "warehouseNo", "WH002",
                    "note", "进水 " + tag, "cabinetNos", List.of(a)), token).okData();
            assertThat(fake.stock).doesNotContainKey(a);
            assertThat(fake.docs.values().stream().anyMatch(x -> "SCRAP".equals(x.purpose()) && "BROKEN".equals(x.reasonCode()))).isTrue();
            jdbc.update("DELETE FROM inv_disposal_item WHERE disposal_no=?", d.path("disposal").path("disposalNo").asText());
            jdbc.update("DELETE FROM inv_disposal WHERE disposal_no=?", d.path("disposal").path("disposalNo").asText());

            // b 不在库存服务的账上：库存服务拒绝 → 本地不报废、不留单
            Resp r = post("/api/ops/inventory/disposals", Map.of("kind", "SCRAP", "reasonCode", "BROKEN", "warehouseNo", "WH002",
                    "note", "进水 " + tag, "cabinetNos", List.of(b)), token);
            assertThat(r.status).isEqualTo(409);
            assertThat(jdbc.queryForObject("SELECT status FROM dev_cabinet WHERE cabinet_no=?", String.class, b)).isEqualTo("IN_STOCK");
        } finally {
            ai.neargo.sharehub.support.FixtureCleanup.dropCabinets(jdbc, List.of(a, b));
        }
    }

    @Test
    @DisplayName("引擎接口报连通性（M0 0.3）：可达 / 不可达带对方错误码 / LOCAL 不探测；寻址方式为固定地址")
    void engineReportsConnectivity() {
        String token = login("ADMIN");
        JsonNode ok = get("/api/ops/inventory/engine", token).okData();
        assertThat(ok.path("engine").asText()).isEqualTo("SERVICE");
        assertThat(ok.path("reachable").asBoolean()).isTrue();
        assertThat(ok.path("endpointMode").asText()).isEqualTo("STATIC");

        remote.useApi(new InventoryFake() {
            @Override
            public List<Warehouse> warehouses() {
                throw new ai.neargo.sharehub.inv.remote.InventoryRemoteException(503, "inventory.unreachable", List.of(), "down", null);
            }
        });
        JsonNode down = get("/api/ops/inventory/engine", token).okData();
        assertThat(down.path("reachable").asBoolean()).isFalse();
        assertThat(down.path("failure").asText()).isEqualTo("inventory.unreachable");

        jdbc.update("UPDATE sys_param SET value='LOCAL' WHERE param_key='inv.engine'");
        JsonNode local = get("/api/ops/inventory/engine", token).okData();
        assertThat(local.path("engine").asText()).isEqualTo("LOCAL");
        assertThat(local.path("reachable").isNull()).isTrue();
    }
}
