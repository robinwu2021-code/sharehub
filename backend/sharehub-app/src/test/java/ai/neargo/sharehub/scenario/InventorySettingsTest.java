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

/** 库存设置与供应商延伸（V207）：充电宝免检策略、供应商关联厂商与绩效。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventorySettingsTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
    private final List<String> cabinets = new ArrayList<>();
    private final List<String> powerbanks = new ArrayList<>();
    private final List<String> intakes = new ArrayList<>();
    private String supplierNo;

    @AfterAll
    void cleanup() {
        jdbc.update("UPDATE sys_param SET value='FULL' WHERE param_key='inv.qc.powerbank_policy'");
        for (String no : intakes) {
            jdbc.update("DELETE FROM inv_intake_item WHERE intake_no=?", no);
            jdbc.update("DELETE FROM inv_intake WHERE intake_no=?", no);
        }
        powerbanks.forEach(no -> jdbc.update("DELETE FROM dev_powerbank WHERE powerbank_no=?", no));
        ai.neargo.sharehub.support.FixtureCleanup.dropCabinets(jdbc, cabinets);
        if (supplierNo != null) jdbc.update("DELETE FROM inv_supplier WHERE supplier_no=?", supplierNo);
    }

    @Test
    @DisplayName("★ 宝免检：采购到货的宝登记即入库，柜仍待检；供应商绩效按到货 / 入库计数")
    void powerbankSkipAndStats() {
        String token = login("ADMIN");
        assertThat(jdbc.queryForObject("SELECT value FROM sys_param WHERE param_key='inv.qc.powerbank_policy'", String.class)).isEqualTo("FULL");
        jdbc.update("UPDATE sys_param SET value='SKIP' WHERE param_key='inv.qc.powerbank_policy'");
        post("/api/platform/sys-params/inv.qc.powerbank_policy", Map.of("paramKey", "inv.qc.powerbank_policy", "value", "SKIP"), token);

        supplierNo = post("/api/ops/inventory/suppliers", Map.of("name", "绩效供应商-" + tag, "vendorCode", "TEST"), token)
                .okData().path("supplierNo").asText();
        assertThat(get("/api/ops/inventory/suppliers?keyword=" + tag, token).okData().path("list").get(0).path("vendorCode").asText()).isEqualTo("TEST");

        String cab = "CABST" + tag;
        post("/api/ops/cabinets", Map.of("cabinetNo", cab, "sn", cab, "vendorCode", "TEST", "slotTotal", 8), token).okData();
        jdbc.update("UPDATE dev_cabinet SET warehouse_no = NULL WHERE cabinet_no = ?", cab);
        cabinets.add(cab);
        String pb = "PBST" + tag;
        jdbc.update("INSERT INTO dev_powerbank (powerbank_no, tenant_id, sn, vendor_code, status) VALUES (?, 'MAIN', ?, 'TEST', 'IN_STOCK')", pb, pb);
        powerbanks.add(pb);

        JsonNode d = post("/api/ops/inventory/intakes", Map.of("warehouseNo", "WH002", "supplierNo", supplierNo,
                "cabinetNos", List.of(cab), "powerbankNos", List.of(pb)), token).okData();
        intakes.add(d.path("intake").path("intakeNo").asText());
        assertThat(d.path("intake").path("inbound").asInt()).as("宝免检直接入库").isEqualTo(1);
        assertThat(d.path("intake").path("pending").asInt()).as("柜仍待检").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT warehouse_no FROM dev_powerbank WHERE powerbank_no=?", String.class, pb)).isEqualTo("WH002");

        JsonNode mine = null;
        for (JsonNode s : get("/api/ops/inventory/suppliers/stats", token).okData())
            if (supplierNo.equals(s.path("supplierNo").asText())) mine = s;
        assertThat(mine).isNotNull();
        assertThat(mine.path("received").asInt()).isEqualTo(2);
        assertThat(mine.path("inbound").asInt()).isEqualTo(1);
        assertThat(mine.path("passRate").asDouble()).isEqualTo(1.0);
    }
}
