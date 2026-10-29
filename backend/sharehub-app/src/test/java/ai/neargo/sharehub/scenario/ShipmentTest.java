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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 发货物流（V205）：调拨发出 / 退供应商随动作记物流，可按单据或设备号查；不填不记。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShipmentTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
    private int seq;
    private final List<String> cabinets = new ArrayList<>();
    private final List<String> transfers = new ArrayList<>();
    private final List<String> disposals = new ArrayList<>();
    private String supplierNo;

    @AfterAll
    void cleanup() {
        for (String t : transfers) {
            jdbc.update("DELETE FROM inv_shipment_item WHERE shipment_no IN (SELECT shipment_no FROM inv_shipment WHERE doc_ref=?)", t);
            jdbc.update("DELETE FROM inv_shipment WHERE doc_ref=?", t);
            jdbc.update("DELETE FROM inv_transfer_item WHERE transfer_no=?", t);
            jdbc.update("DELETE FROM inv_transfer WHERE transfer_no=?", t);
        }
        for (String d : disposals) {
            jdbc.update("DELETE FROM inv_shipment_item WHERE shipment_no IN (SELECT shipment_no FROM inv_shipment WHERE doc_ref=?)", d);
            jdbc.update("DELETE FROM inv_shipment WHERE doc_ref=?", d);
            jdbc.update("DELETE FROM inv_disposal_item WHERE disposal_no=?", d);
            jdbc.update("DELETE FROM inv_disposal WHERE disposal_no=?", d);
        }
        ai.neargo.sharehub.support.FixtureCleanup.dropCabinets(jdbc, cabinets);
        if (supplierNo != null) jdbc.update("DELETE FROM inv_supplier WHERE supplier_no=?", supplierNo);
    }

    private String stockCabinet(String token, String wh) {
        String no = "CABSH" + tag + (++seq);
        post("/api/ops/cabinets", Map.of("cabinetNo", no, "sn", no, "vendorCode", "TEST", "slotTotal", 8), token).okData();
        jdbc.update("UPDATE dev_cabinet SET warehouse_no = ?, qc_status = 'PASSED' WHERE cabinet_no = ?", wh, no);
        cabinets.add(no);
        return no;
    }

    private String transferOf(String token, String cab) {
        String t = post("/api/ops/inventory-transfers", Map.of("fromType", "WAREHOUSE", "fromRef", "WH002", "toType", "WAREHOUSE",
                "toRef", "WH003", "itemType", "CABINET"), token).okData().path("transferNo").asText();
        transfers.add(t);
        Resp r = post("/api/ops/inventory-transfers/" + t + "/items", Map.of("itemNos", List.of(cab)), token);
        assertThat(r.status).as(r.body.toString()).isEqualTo(200);
        return t;
    }

    @Test
    @DisplayName("调拨发出带物流 → 按单据、按设备号都查得到；不带物流的发出不记")
    void transferShip() {
        String token = login("ADMIN");
        String c = stockCabinet(token, "WH002");
        String t = transferOf(token, c);
        post("/api/ops/inventory-transfers/" + t + "/ship", Map.of("carrier", "Aramex", "trackingNo", "AX" + tag), token).okData();
        JsonNode byDoc = get("/api/ops/inventory/shipments?docType=TRANSFER&docRef=" + t, token).okData();
        assertThat(byDoc).hasSize(1);
        assertThat(byDoc.get(0).path("trackingNo").asText()).isEqualTo("AX" + tag);
        assertThat(byDoc.get(0).path("itemNos").get(0).asText()).isEqualTo(c);
        assertThat(get("/api/ops/inventory/shipments?itemNo=" + c, token).okData()).hasSize(1);

        String c2 = stockCabinet(token, "WH002");
        String t2 = transferOf(token, c2);
        post("/api/ops/inventory-transfers/" + t2 + "/ship", null, token).okData();
        assertThat(get("/api/ops/inventory/shipments?docRef=" + t2, token).okData()).isEmpty();
    }

    @Test
    @DisplayName("退供应商带物流；超长运单号在动作前就被拒（不出库）")
    void returnSupplier() {
        String token = login("ADMIN");
        supplierNo = post("/api/ops/inventory/suppliers", Map.of("name", "物流供应商-" + tag), token).okData().path("supplierNo").asText();
        String c = stockCabinet(token, "WH002");
        Map<String, Object> body = new HashMap<>(Map.of("kind", "RETURN_SUPPLIER", "warehouseNo", "WH002", "supplierNo", supplierNo,
                "note", "退货", "cabinetNos", List.of(c), "carrier", "DHL", "trackingNo", "X".repeat(65)));
        assertThat(post("/api/ops/inventory/disposals", body, token).status).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT status FROM dev_cabinet WHERE cabinet_no=?", String.class, c)).isEqualTo("IN_STOCK");
        body.put("trackingNo", "DHL" + tag);
        String d = post("/api/ops/inventory/disposals", body, token).okData().path("disposal").path("disposalNo").asText();
        disposals.add(d);
        JsonNode s = get("/api/ops/inventory/shipments?docType=DISPOSAL&docRef=" + d, token).okData();
        assertThat(s).hasSize(1);
        assertThat(s.get(0).path("carrier").asText()).isEqualTo("DHL");
    }
}
