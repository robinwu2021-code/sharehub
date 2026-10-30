package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.inv.remote.HttpInventoryApi;
import ai.neargo.sharehub.inv.remote.RemoteInventoryPort;
import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.InventoryFake;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class StockCountScopeTest extends ApiTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired RemoteInventoryPort remote;
    @Autowired HttpInventoryApi httpApi;
    InventoryFake fake;
    final String tag=Long.toUnsignedString(System.nanoTime(),36);
    final String own="WHCA"+tag, other="WHCB"+tag, who="CS"+tag;
    String admin, token, ownCount, foreignCount;

    @BeforeEach void setup() {
        fake=new InventoryFake(); remote.useApi(fake);
        jdbc.update("UPDATE sys_param SET value='SERVICE' WHERE param_key='inv.engine'");
        jdbc.update("DELETE FROM inventory_sync_state");
        jdbc.update("INSERT INTO inv_warehouse(warehouse_no,name,region_id) VALUES (?,?,'COUNT-A'),(?,?,'COUNT-B')",own,own,other,other);
        admin=login("ADMIN"); token=login("WAREHOUSE",who,null);
        put("/api/platform/data-scopes/EMPLOYEE/"+who,Map.of("scopeType","REGION","scopeRefs","COUNT-A"),admin).okData();
        ownCount=post("/api/ops/inventory/counts",Map.of("warehouseNo",own),admin).okData().path("count").path("countNo").asText();
        foreignCount=post("/api/ops/inventory/counts",Map.of("warehouseNo",other),admin).okData().path("count").path("countNo").asText();
    }

    @AfterEach void cleanup() {
        remote.useApi(httpApi);
        jdbc.update("UPDATE sys_param SET value='LOCAL' WHERE param_key='inv.engine'");
        jdbc.update("DELETE FROM inventory_sync_state");
        jdbc.update("DELETE FROM inv_warehouse WHERE warehouse_no IN (?,?)",own,other);
        put("/api/platform/data-scopes/EMPLOYEE/"+who,Map.of("scopeType","ALL","scopeRefs",""),admin).okData();
    }

    @ParameterizedTest @ValueSource(strings={"scans","submit","approve","reject","post","cancel"})
    void foreignCountIsRejectedBeforeAnyRemoteMutation(String action) {
        String initial=List.of("approve","reject").contains(action)?"SUBMITTED":"post".equals(action)?"APPROVED":"OPEN";
        fake.counts.get(foreignCount)[1]=initial;
        Map<String,?> body="scans".equals(action)?Map.of("serialNos",List.of("OTHER-SN")):Map.of("note","scope test");
        assertThat(post("/api/ops/inventory/counts/"+foreignCount+"/"+action,body,token).status).isEqualTo(403);
        assertThat(fake.counts.get(foreignCount)[1]).isEqualTo(initial);
        assertThat((List<?>)fake.counts.get(foreignCount)[2]).isEmpty();
    }

    @Test void listDetailAndOpeningRespectWarehouseScope() {
        get("/api/ops/inventory/counts/"+ownCount,token).okData();
        assertThat(get("/api/ops/inventory/counts/"+foreignCount,token).status).isEqualTo(403);
        assertThat(get("/api/ops/inventory/counts",token).okData().findValuesAsText("countNo")).contains(ownCount).doesNotContain(foreignCount);
        assertThat(post("/api/ops/inventory/counts",Map.of("warehouseNo",other),token).status).isEqualTo(403);
        assertThat(fake.counts).hasSize(2);
    }

    @Test void scopedRemoteLedgerDoesNotReadTheGlobalWindow() {
        get("/api/ops/inventory/ledger",token).okData();
        assertThat(fake.ledgerLocations).containsExactly((String)fake.counts.get(ownCount)[0]);
    }
}
