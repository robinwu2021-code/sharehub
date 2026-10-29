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

/** 处置出库（V203）：在库设备报废 / 退供应商，建单即过账；规则冲突一次列全。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DisposalTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
    private int seq;
    private final List<String> cabinets = new ArrayList<>();
    private final List<String> docs = new ArrayList<>();
    private String supplierNo;

    @AfterAll
    void cleanup() {
        for (String no : docs) {
            jdbc.update("DELETE FROM inv_disposal_item WHERE disposal_no=?", no);
            jdbc.update("DELETE FROM inv_disposal WHERE disposal_no=?", no);
        }
        ai.neargo.sharehub.support.FixtureCleanup.dropCabinets(jdbc, cabinets);
        if (supplierNo != null) jdbc.update("DELETE FROM inv_supplier WHERE supplier_no=?", supplierNo);
    }

    /** 在 WH002 在库的柜。 */
    private String stockCabinet(String token) {
        String no = "CABD" + tag + (++seq);
        post("/api/ops/cabinets", Map.of("cabinetNo", no, "sn", no, "vendorCode", "TEST", "slotTotal", 8), token).okData();
        jdbc.update("UPDATE dev_cabinet SET warehouse_no = 'WH002' WHERE cabinet_no = ?", no);
        cabinets.add(no);
        return no;
    }

    @Test
    @DisplayName("报废：须原因码；过账后柜报废、履历记 RETIRE、单据可查")
    void scrap() {
        String token = login("ADMIN");
        String c = stockCabinet(token);
        assertThat(post("/api/ops/inventory/disposals", Map.of("kind", "SCRAP", "warehouseNo", "WH002", "note", "进水",
                "cabinetNos", List.of(c)), token).status).isEqualTo(400);
        JsonNode d = post("/api/ops/inventory/disposals", Map.of("kind", "SCRAP", "warehouseNo", "WH002", "reasonCode", "BROKEN",
                "note", "进水", "cabinetNos", List.of(c)), token).okData();
        String no = d.path("disposal").path("disposalNo").asText();
        docs.add(no);
        assertThat(d.path("disposal").path("itemCount").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM dev_cabinet WHERE cabinet_no=?", String.class, c)).isEqualTo("RETIRED");
        assertThat(get("/api/ops/inventory/disposals?keyword=" + c, token).okData().path("total").asInt()).isEqualTo(1);
        // 已报废的不能再处置
        assertThat(post("/api/ops/inventory/disposals", Map.of("kind", "SCRAP", "warehouseNo", "WH002", "reasonCode", "BROKEN",
                "note", "x", "cabinetNos", List.of(c)), token).status).isEqualTo(409);
    }

    @Test
    @DisplayName("退供应商：须供应商、不能带原因码之外的混用；不在该仓的柜一次列出")
    void returnSupplier() {
        String token = login("ADMIN");
        supplierNo = post("/api/ops/inventory/suppliers", Map.of("name", "退货供应商-" + tag), token).okData().path("supplierNo").asText();
        String a = stockCabinet(token), b = stockCabinet(token);
        jdbc.update("UPDATE dev_cabinet SET warehouse_no = 'WH003' WHERE cabinet_no = ?", b);
        assertThat(post("/api/ops/inventory/disposals", Map.of("kind", "RETURN_SUPPLIER", "warehouseNo", "WH002", "note", "批次不良",
                "cabinetNos", List.of(a)), token).status).isEqualTo(400);
        assertThat(post("/api/ops/inventory/disposals", Map.of("kind", "SCRAP", "warehouseNo", "WH002", "reasonCode", "BROKEN",
                "supplierNo", supplierNo, "note", "x", "cabinetNos", List.of(a)), token).status).isEqualTo(400);
        Resp r = post("/api/ops/inventory/disposals", Map.of("kind", "RETURN_SUPPLIER", "warehouseNo", "WH002", "supplierNo", supplierNo,
                "note", "批次不良", "cabinetNos", List.of(a, b)), token);
        assertThat(r.status).isEqualTo(409);
        assertThat(r.body.toString()).contains(b);
        JsonNode d = post("/api/ops/inventory/disposals", Map.of("kind", "RETURN_SUPPLIER", "warehouseNo", "WH002", "supplierNo", supplierNo,
                "note", "批次不良", "cabinetNos", List.of(a)), token).okData();
        docs.add(d.path("disposal").path("disposalNo").asText());
        assertThat(d.path("disposal").path("supplierName").asText()).isEqualTo("退货供应商-" + tag);
        assertThat(post("/api/ops/inventory/disposals", Map.of("kind", "SCRAP", "warehouseNo", "WH002", "reasonCode", "BROKEN",
                "note", "x", "cabinetNos", List.of(b)), login("VIEWER")).status).isEqualTo(403);
    }
}
