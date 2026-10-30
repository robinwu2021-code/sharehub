package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.FixtureCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 通用开单API也必须执行商家出库约束，不以抽屉筛选代替服务端校验。 */
class InstallWorkOrderEligibilityTest extends ApiTestSupport {
    @Autowired JdbcTemplate jdbc;
    final List<String> cabinets = new ArrayList<>();
    final String merchant = "AG-INSTALL-A";

    @AfterEach void cleanup() { FixtureCleanup.dropCabinets(jdbc, cabinets); }

    String cabinet(String status, String issuedAgent, boolean claimed, String warehouse) {
        String c = "CWI" + Long.toUnsignedString(System.nanoTime(), 36);
        cabinets.add(c);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no,tenant_id,sn,vendor_code,status,qc_status,issued_agent_no,issue_claimed_at,warehouse_no) VALUES (?,'MAIN',?,'TEST',?,'PASSED',?, ?, ?)",
                c, c, status, issuedAgent, claimed ? java.time.LocalDateTime.now() : null, warehouse);
        return c;
    }

    Map<String, Object> request(String c) {
        return new HashMap<>(Map.of("type", "INSTALL", "priority", "MEDIUM", "cabinetNo", c, "agentNo", merchant, "description", "安装测试"));
    }

    @Test void warehouseAssetCannotBeInstalledThroughGenericCreateApi() {
        String c = cabinet("IN_STOCK", null, false, "WH002");
        assertThat(post("/api/ops/work-orders", request(c), login("OPS")).status).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wo_order WHERE cabinet_no=?", Integer.class, c)).isZero();
    }

    @Test void merchantIsMandatoryEvenWhenClientSpoofsInternalBindingSource() {
        var body = request(cabinet("IN_STOCK", merchant, true, null));
        body.remove("agentNo");
        body.put("sourceNo", "BIND:spoof:point");
        assertThat(post("/api/ops/work-orders", body, login("OPS")).status).isEqualTo(400);
    }

    @Test void differentMerchantAndUnclaimedDeliveryAreRejected() {
        assertThat(post("/api/ops/work-orders", request(cabinet("IN_STOCK", "AG-INSTALL-B", true, null)), login("OPS")).status).isEqualTo(409);
        assertThat(post("/api/ops/work-orders", request(cabinet("IN_STOCK", merchant, false, null)), login("OPS")).status).isEqualTo(409);
    }

    @Test void liveAndFailedQualityAssetsAreRejected() {
        assertThat(post("/api/ops/work-orders", request(cabinet("DEPLOYED", merchant, true, null)), login("OPS")).status).isEqualTo(409);
        String failed = cabinet("IN_STOCK", merchant, true, null);
        jdbc.update("UPDATE dev_cabinet SET qc_status='FAILED' WHERE cabinet_no=?", failed);
        assertThat(post("/api/ops/work-orders", request(failed), login("OPS")).status).isEqualTo(409);
    }

    @Test void eligibleIssuedAssetDerivesScopeFromValidatedDelivery() {
        String c = cabinet("IN_STOCK", merchant, true, null);
        var body = request(c);
        body.put("siteNo", "FAKE-SITE");
        body.put("locationNo", "FAKE-POINT");
        String wo = post("/api/ops/work-orders", body, login("OPS")).okData().path("woNo").asText();
        assertThat(jdbc.queryForMap("SELECT agent_no,site_no,location_no FROM wo_order WHERE wo_no=?", wo))
                .containsEntry("agent_no", merchant).containsEntry("site_no", null).containsEntry("location_no", null);
    }
}
