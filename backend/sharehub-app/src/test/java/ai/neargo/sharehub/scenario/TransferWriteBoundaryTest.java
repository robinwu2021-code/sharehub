package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.FixtureCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TransferWriteBoundaryTest extends ApiTestSupport {
    @Autowired JdbcTemplate jdbc;
    final List<String> cabinets = new ArrayList<>();

    @AfterEach
    void cleanup() { FixtureCleanup.dropCabinets(jdbc, cabinets); }

    private String[] transfer(String token) {
        String c = "CBW" + Long.toUnsignedString(System.nanoTime(), 36);
        cabinets.add(c);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no,tenant_id,sn,vendor_code,status,qc_status,warehouse_no) VALUES (?, 'MAIN', ?, 'TEST','IN_STOCK','PASSED','WH002')", c, c);
        String t = post("/api/ops/inventory-transfers", Map.of("fromType", "WAREHOUSE", "fromRef", "WH002", "toType", "WAREHOUSE", "toRef", "WH001", "itemType", "CABINET"), token)
                .okData().path("transferNo").asText();
        jdbc.update("INSERT INTO inv_transfer_item (transfer_no, cabinet_no, checked) VALUES (?, ?, 0)", t, c);
        return new String[]{t, c};
    }

    @Test
    void savingAHeaderCannotShipEvenWhenItHasValidItems() {
        String token = login("ADMIN");
        String[] pair = transfer(token);
        assertThat(post("/api/ops/inventory-transfers/" + pair[0], Map.of("status", "IN_TRANSIT"), token).status).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT status FROM inv_transfer WHERE transfer_no=?", String.class, pair[0])).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT status FROM dev_cabinet WHERE cabinet_no=?", String.class, pair[1])).isEqualTo("IN_STOCK");
        assertThat(jdbc.queryForObject("SELECT shipped_at FROM inv_transfer WHERE transfer_no=?", String.class, pair[0])).isNull();
    }

    @Test
    void savingAHeaderCannotReceiveEvenWhenEveryItemWasMarkedChecked() {
        String token = login("ADMIN");
        String[] pair = transfer(token);
        post("/api/ops/inventory-transfers/" + pair[0] + "/ship", Map.of(), token).okData();
        jdbc.update("UPDATE inv_transfer_item SET checked=1 WHERE transfer_no=?", pair[0]);
        assertThat(post("/api/ops/inventory-transfers/" + pair[0], Map.of("status", "DONE"), token).status).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT status FROM inv_transfer WHERE transfer_no=?", String.class, pair[0])).isEqualTo("IN_TRANSIT");
        assertThat(jdbc.queryForObject("SELECT status FROM dev_cabinet WHERE cabinet_no=?", String.class, pair[1])).isEqualTo("IN_TRANSIT");
        assertThat(jdbc.queryForObject("SELECT received_at FROM inv_transfer WHERE transfer_no=?", String.class, pair[0])).isNull();
    }

    @Test
    void explicitShippingAndReceivingKeepDeviceAndTransferInSync() {
        String token = login("ADMIN");
        String[] pair = transfer(token);
        post("/api/ops/inventory-transfers/" + pair[0] + "/ship", Map.of(), token).okData();
        post("/api/ops/inventory-transfers/" + pair[0] + "/receive", Map.of("receivedNos", List.of(pair[1])), token).okData();
        assertThat(jdbc.queryForObject("SELECT status FROM inv_transfer WHERE transfer_no=?", String.class, pair[0])).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT warehouse_no FROM dev_cabinet WHERE cabinet_no=?", String.class, pair[1])).isEqualTo("WH001");
    }
}
