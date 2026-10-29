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

/** 仓库管理与供应商（V201）：增改归档、归档仓拒收、默认回仓仓不能归档、到货登记带供应商。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupplierWarehouseAdminTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
    private final List<String> warehouses = new ArrayList<>();
    private final List<String> suppliers = new ArrayList<>();
    private final List<String> cabinets = new ArrayList<>();
    private final List<String> intakes = new ArrayList<>();

    @AfterAll
    void cleanup() {
        for (String no : intakes) {
            jdbc.update("DELETE FROM inv_intake_item WHERE intake_no=?", no);
            jdbc.update("DELETE FROM inv_intake WHERE intake_no=?", no);
        }
        ai.neargo.sharehub.support.FixtureCleanup.dropCabinets(jdbc, cabinets);
        warehouses.forEach(no -> jdbc.update("DELETE FROM inv_warehouse WHERE warehouse_no=?", no));
        suppliers.forEach(no -> jdbc.update("DELETE FROM inv_supplier WHERE supplier_no=?", no));
    }

    private String newWarehouse(String token) {
        JsonNode w = post("/api/ops/inventory/warehouses", Map.of("name", "测试仓-" + tag, "address", "A"), token).okData();
        String no = w.path("warehouseNo").asText();
        warehouses.add(no);
        return no;
    }

    @Test
    @DisplayName("仓库：新建 → 编辑 → 归档后不能收货 → 取消归档")
    void warehouseLifecycle() {
        String token = login("ADMIN");
        String no = newWarehouse(token);
        JsonNode u = post("/api/ops/inventory/warehouses/" + no, Map.of("name", "改名仓-" + tag), token).okData();
        assertThat(u.path("name").asText()).isEqualTo("改名仓-" + tag);
        assertThat(u.path("archivedAt").isNull()).isTrue();

        assertThat(post("/api/ops/inventory/warehouses/" + no + "/archive", Map.of(), token).okData()
                .path("archivedAt").isNull()).isFalse();
        String cab = "CABW" + tag;
        post("/api/ops/cabinets", Map.of("cabinetNo", cab, "sn", cab, "vendorCode", "TEST", "slotTotal", 8), token).okData();
        cabinets.add(cab);
        jdbc.update("UPDATE dev_cabinet SET warehouse_no = NULL WHERE cabinet_no = ?", cab);
        assertThat(post("/api/ops/inventory/intakes", Map.of("warehouseNo", no, "cabinetNos", List.of(cab)), token).status)
                .isEqualTo(400);

        post("/api/ops/inventory/warehouses/" + no + "/unarchive", Map.of(), token).okData();
        JsonNode d = post("/api/ops/inventory/intakes", Map.of("warehouseNo", no, "cabinetNos", List.of(cab)), token).okData();
        intakes.add(d.path("intake").path("intakeNo").asText());
        // 有未结待检单 → 不能归档
        Resp r = post("/api/ops/inventory/warehouses/" + no + "/archive", Map.of(), token);
        assertThat(r.status).isEqualTo(409);
    }

    @Test
    @DisplayName("默认回仓仓不能归档；无 device:inventory:warehouse 的仓管不能建仓")
    void guards() {
        String token = login("ADMIN");
        String def = jdbc.queryForObject("SELECT value FROM sys_param WHERE tenant_id='MAIN' AND param_key='inv.return_warehouse'", String.class);
        assertThat(post("/api/ops/inventory/warehouses/" + def + "/archive", Map.of(), token).status).isEqualTo(409);
        assertThat(post("/api/ops/inventory/warehouses", Map.of("name", "x"), login("WAREHOUSE")).status).isEqualTo(403);
    }

    @Test
    @DisplayName("供应商：新建 / 编辑 / 归档；到货登记记下供应商，已归档供应商不能选")
    void supplier() {
        String token = login("ADMIN");
        JsonNode s = post("/api/ops/inventory/suppliers", Map.of("name", "供应商-" + tag, "phone", "0500000000"), token).okData();
        String no = s.path("supplierNo").asText();
        suppliers.add(no);
        assertThat(post("/api/ops/inventory/suppliers/" + no, Map.of("name", "供应商B-" + tag), token).okData()
                .path("name").asText()).isEqualTo("供应商B-" + tag);
        assertThat(get("/api/ops/inventory/suppliers?keyword=" + tag, token).okData().path("total").asInt()).isEqualTo(1);

        String cab = "CABS" + tag;
        post("/api/ops/cabinets", Map.of("cabinetNo", cab, "sn", cab, "vendorCode", "TEST", "slotTotal", 8), token).okData();
        cabinets.add(cab);
        jdbc.update("UPDATE dev_cabinet SET warehouse_no = NULL WHERE cabinet_no = ?", cab);
        JsonNode d = post("/api/ops/inventory/intakes", Map.of("warehouseNo", "WH002", "supplierNo", no, "cabinetNos", List.of(cab)), token).okData();
        intakes.add(d.path("intake").path("intakeNo").asText());
        assertThat(d.path("intake").path("supplierName").asText()).isEqualTo("供应商B-" + tag);

        post("/api/ops/inventory/suppliers/" + no + "/archive", Map.of(), token).okData();
        assertThat(get("/api/ops/inventory/suppliers?keyword=" + tag, token).okData().path("total").asInt()).isZero();
        assertThat(get("/api/ops/inventory/suppliers?showArchived=true&keyword=" + tag, token).okData().path("total").asInt()).isEqualTo(1);
        assertThat(post("/api/ops/inventory/intakes", Map.of("warehouseNo", "WH002", "supplierNo", no, "cabinetNos", List.of("CABX" + tag)), token).status)
                .isEqualTo(400);
        post("/api/ops/inventory/suppliers/" + no + "/unarchive", Map.of(), token).okData();
    }
}
