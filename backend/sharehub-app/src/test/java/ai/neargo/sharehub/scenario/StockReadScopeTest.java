package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.FixtureCleanup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class StockReadScopeTest extends ApiTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired ai.neargo.sharehub.dev.port.PowerbankTracker tracker;
    @Autowired ai.neargo.sharehub.dev.service.PowerbankLossService losses;
    final String tag=Long.toUnsignedString(System.nanoTime(),36);
    final String own="WHSA"+tag, other="WHSB"+tag, cab="CBSA"+tag, foreign="CBSB"+tag, who="SR"+tag;
    String admin, token;
    @BeforeEach void setup() {
        admin=login("ADMIN"); token=login("WAREHOUSE",who,null);
        jdbc.update("INSERT INTO inv_warehouse(warehouse_no,name,region_id) VALUES (?,?,'STOCK-A'),(?,?,'STOCK-B')",own,own,other,other);
        for(String no:List.of(cab,foreign)) {
            String warehouse=no.equals(cab)?own:other;
            // 资产region故意相同，证明仓储权限不能只靠设备的旧区域字段。
            jdbc.update("INSERT INTO dev_cabinet(cabinet_no,sn,vendor_code,status,qc_status,region_id,warehouse_no) VALUES (?,?,'TEST','IN_STOCK','PASSED','STOCK-A',?)",no,no,warehouse);
            jdbc.update("INSERT INTO dev_movement_log(item_type,item_no,event,to_ref,occurred_at,operator_no) VALUES ('CABINET',?,'INBOUND',?,NOW(3),'SYSTEM')",no,"WH:"+warehouse);
        }
        put("/api/platform/data-scopes/EMPLOYEE/"+who,Map.of("scopeType","REGION","scopeRefs","STOCK-A"),admin).okData();
    }
    @AfterEach void cleanup() {
        FixtureCleanup.dropCabinets(jdbc,List.of(cab,foreign));
        jdbc.update("DELETE FROM inv_warehouse WHERE warehouse_no IN (?,?)",own,other);
        put("/api/platform/data-scopes/EMPLOYEE/"+who,Map.of("scopeType","ALL","scopeRefs",""),admin).okData();
    }
    @Test void stageTotalsAndDrilldownExcludeForeignWarehouse() {
        var stage=get("/api/ops/inventory/stage-items?stage=IN_STOCK&itemType=CABINET",token).okData();
        assertThat(stage.path("items").findValuesAsText("itemNo")).containsExactly(cab);
        assertThat(stage.path("total").asInt()).isEqualTo(1);
        var stages=get("/api/ops/inventory/stages",token).okData();
        for(var row:stages) if("IN_STOCK".equals(row.path("stage").asText())) assertThat(row.path("cabinets").asInt()).isEqualTo(1);
    }
    @Test void ledgerFiltersBeforePaginationAndDoesNotLeakForeignTotals() {
        var rows=get("/api/ops/inventory/ledger?page=1&size=1",token).okData();
        assertThat(rows.path("total").asInt()).isEqualTo(1);
        assertThat(rows.path("list").findValuesAsText("itemNo")).containsExactly(cab);
        assertThat(get("/api/ops/inventory/ledger?warehouseNo="+other,token).status).isEqualTo(403);
    }
    @Test void periodSummaryOnlyReturnsAuthorizedWarehouses() {
        String query="/api/ops/inventory/period-summary?from="+bizToday().minusDays(1)+"&to="+bizToday();
        assertThat(get(query,token).okData().findValuesAsText("warehouseNo")).contains(own).doesNotContain(other);
        assertThat(get(query+"&warehouseNo="+other,token).status).isEqualTo(403);
    }
    @Test void staleWarehouseCannotOverrideCurrentCabinetAndLatestOrderOwnsOffCabinetAssets() {
        String outside="CBX"+tag, held="PBH"+tag;
        var pbs=List.of(held,"PBR"+tag,"PBL"+tag,"PBS"+tag,"PBX"+tag);
        jdbc.update("INSERT INTO dev_cabinet(cabinet_no,sn,vendor_code,status,region_id) VALUES (?,?,'TEST','DEPLOYED','STOCK-B')",outside,outside);
        try {
            jdbc.update("INSERT INTO dev_powerbank(powerbank_no,sn,vendor_code,status,cabinet_no,warehouse_no) VALUES (?,?,'TEST','IN_CABINET',?,?)",held,held,outside,own);
            for(int i=1;i<pbs.size();i++) {
                String pb=pbs.get(i);
                jdbc.update("INSERT INTO dev_powerbank(powerbank_no,sn,vendor_code,status,cabinet_no,warehouse_no) VALUES (?,?,'TEST','IN_CABINET',?,?)",pb,pb,cab,own);
                tracker.rented(pb,"ORA"+pb);
                if(i==2) {
                    jdbc.update("UPDATE dev_powerbank SET suspected_lost_at=NOW() WHERE powerbank_no=?",pb);
                    losses.confirmLost(pb,"范围回归：确认遗失");
                }
                if(i==3) tracker.sold(pb);
                jdbc.update("INSERT INTO ord_order(order_no,c_user_no,cabinet_no,status,powerbank_no,region_id) VALUES (?,'SCOPE-TEST',?,'IN_USE',?,'STOCK-A')","ORA"+pb,cab,pb);
                if(i==4) jdbc.update("INSERT INTO ord_order(order_no,c_user_no,cabinet_no,status,powerbank_no,region_id) VALUES (?,'SCOPE-TEST',?,'IN_USE',?,'STOCK-B')","ORB"+pb,outside,pb);
            }
            assertThat(get("/api/ops/inventory/stage-items?stage=LIVE&itemType=POWERBANK",token).okData().path("items").findValuesAsText("itemNo")).doesNotContain(held);
            for(String stage:List.of("RENTED","LOST","RETIRED")) {
                var nos=get("/api/ops/inventory/stage-items?stage="+stage+"&itemType=POWERBANK",token).okData().path("items").findValuesAsText("itemNo");
                assertThat(nos).contains(pbs.get(stage.equals("RENTED")?1:stage.equals("LOST")?2:3)).doesNotContain(pbs.get(4));
            }
        } finally {
            for(String pb:pbs){jdbc.update("DELETE FROM ord_order WHERE powerbank_no=?",pb);jdbc.update("DELETE FROM dev_powerbank WHERE powerbank_no=?",pb);}
            FixtureCleanup.dropCabinets(jdbc,List.of(outside));
        }
    }

    @Test void scrapKeepsActualOwnerSnapshotAndIgnoresStaleWarehouse() {
        String outside="CBZ"+tag,pb="PBZ"+tag,whoB="SRB"+tag;
        String tokenB=login("WAREHOUSE",whoB,null);
        jdbc.update("INSERT INTO dev_cabinet(cabinet_no,sn,vendor_code,status,region_id) VALUES (?,?,'TEST','DEPLOYED','STOCK-B')",outside,outside);
        jdbc.update("INSERT INTO dev_powerbank(powerbank_no,sn,vendor_code,status,cabinet_no,warehouse_no) VALUES (?,?,'TEST','IN_CABINET',?,?)",pb,pb,outside,own);
        try {
            put("/api/platform/data-scopes/EMPLOYEE/"+whoB,Map.of("scopeType","REGION","scopeRefs","STOCK-B"),admin).okData();
            post("/api/ops/powerbanks/"+pb,Map.of("event","SCRAP"),admin).okData();
            assertThat(get("/api/ops/inventory/stage-items?stage=RETIRED&itemType=POWERBANK",token).okData().path("items").findValuesAsText("itemNo")).doesNotContain(pb);
            assertThat(get("/api/ops/inventory/stage-items?stage=RETIRED&itemType=POWERBANK",tokenB).okData().path("items").findValuesAsText("itemNo")).contains(pb);
            jdbc.update("UPDATE dev_cabinet SET region_id='STOCK-A' WHERE cabinet_no=?",outside);
            assertThat(get("/api/ops/inventory/stage-items?stage=RETIRED&itemType=POWERBANK",tokenB).okData().path("items").findValuesAsText("itemNo")).contains(pb);
            assertThat(get("/api/ops/inventory/stage-items?stage=RETIRED&itemType=POWERBANK",token).okData().path("items").findValuesAsText("itemNo")).doesNotContain(pb);
        } finally {
            jdbc.update("DELETE FROM dev_movement_log WHERE item_no=?",pb);jdbc.update("DELETE FROM dev_powerbank WHERE powerbank_no=?",pb);
            FixtureCleanup.dropCabinets(jdbc,List.of(outside));
            put("/api/platform/data-scopes/EMPLOYEE/"+whoB,Map.of("scopeType","ALL","scopeRefs",""),admin).okData();
        }
    }

    @Test void warehouseHeldCabinetRetirementUsesPhysicalWarehouseInsteadOfOldCabinetRegion() {
        String pb="PBW"+tag,whoB="SRW"+tag,tokenB=login("WAREHOUSE",whoB,null);
        jdbc.update("INSERT INTO dev_powerbank(powerbank_no,sn,vendor_code,status,cabinet_no,warehouse_no) VALUES (?,?,'TEST','IN_CABINET',?,?)",pb,pb,foreign,own);
        try {
            put("/api/platform/data-scopes/EMPLOYEE/"+whoB,Map.of("scopeType","REGION","scopeRefs","STOCK-B"),admin).okData();
            post("/api/ops/powerbanks/"+pb,Map.of("event","SCRAP"),admin).okData();
            assertThat(get("/api/ops/inventory/stage-items?stage=RETIRED&itemType=POWERBANK",token).okData().path("items").findValuesAsText("itemNo")).doesNotContain(pb);
            assertThat(get("/api/ops/inventory/stage-items?stage=RETIRED&itemType=POWERBANK",tokenB).okData().path("items").findValuesAsText("itemNo")).contains(pb);
        } finally {
            jdbc.update("DELETE FROM dev_movement_log WHERE item_no=?",pb);jdbc.update("DELETE FROM dev_powerbank WHERE powerbank_no=?",pb);
            put("/api/platform/data-scopes/EMPLOYEE/"+whoB,Map.of("scopeType","ALL","scopeRefs",""),admin).okData();
        }
    }

}
