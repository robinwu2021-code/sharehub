package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.FixtureCleanup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class TransferWarehouseScopeTest extends ApiTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired ai.neargo.sharehub.dev.service.CabinetLifecycleService lifecycle;
    @Autowired ai.neargo.sharehub.inv.service.TransferOpsService ops;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ai.neargo.sharehub.dev.movement.MovementRecorder recorder;
    final String tag=Long.toUnsignedString(System.nanoTime(),36);
    final String a="WHTA"+tag,b="WHTB"+tag,c="WHTC"+tag;
    final List<String> transfers=new ArrayList<>(),cabinets=new ArrayList<>(),powerbanks=new ArrayList<>();
    String admin,source,destination;
    @BeforeEach void setup() {
        admin=login("ADMIN");source=login("WAREHOUSE","TA"+tag,null);destination=login("WAREHOUSE","TB"+tag,null);
        jdbc.update("INSERT INTO inv_warehouse(warehouse_no,name,region_id) VALUES (?,?,'TRANSFER-A'),(?,?,'TRANSFER-B'),(?,?,'TRANSFER-C')",a,a,b,b,c,c);
        put("/api/platform/data-scopes/EMPLOYEE/TA"+tag,Map.of("scopeType","REGION","scopeRefs","TRANSFER-A"),admin).okData();
        put("/api/platform/data-scopes/EMPLOYEE/TB"+tag,Map.of("scopeType","REGION","scopeRefs","TRANSFER-B"),admin).okData();
    }
    @AfterEach void cleanup() {
        FixtureCleanup.dropCabinets(jdbc,cabinets);
        for(String no:transfers){jdbc.update("DELETE FROM inv_transfer_item WHERE transfer_no=?",no);jdbc.update("DELETE FROM inv_transfer WHERE transfer_no=?",no);}
        for(String no:powerbanks){jdbc.update("DELETE FROM dev_movement_log WHERE item_no=?",no);jdbc.update("DELETE FROM dev_powerbank WHERE powerbank_no=?",no);}
        jdbc.update("DELETE FROM inv_warehouse WHERE warehouse_no IN (?,?,?)",a,b,c);
        for(String who:List.of("TA"+tag,"TB"+tag)) put("/api/platform/data-scopes/EMPLOYEE/"+who,Map.of("scopeType","ALL","scopeRefs",""),admin).okData();
    }
    Map<String,Object> header(String from,String to){return Map.of("fromType","WAREHOUSE","fromRef",from,"toType","WAREHOUSE","toRef",to,"itemType","CABINET");}
    String transfer(String from,String to){String no=post("/api/ops/inventory-transfers",header(from,to),admin).okData().path("transferNo").asText();transfers.add(no);return no;}
    String cabinet(String warehouse){String no="CBT"+tag+cabinets.size();cabinets.add(no);jdbc.update("INSERT INTO dev_cabinet(cabinet_no,sn,vendor_code,status,qc_status,warehouse_no,region_id) VALUES (?,?,'TEST','IN_STOCK','PASSED',?,'TRANSFER-A')",no,no,warehouse);return no;}
    void items(String t,String cabinet){jdbc.update("INSERT INTO inv_transfer_item(transfer_no,cabinet_no,checked) VALUES (?,?,0)",t,cabinet);}
    @Test void readEitherEndpointButCannotCreateOrRewriteForeignEndpoints(){
        String hidden=transfer(b,c),incoming=transfer(b,a),outgoing=transfer(a,b);
        assertThat(get("/api/ops/inventory-transfers?keyword="+hidden,source).okData().path("total").asInt()).isZero();
        assertThat(get("/api/ops/inventory-transfers/"+hidden,source).status).isEqualTo(403);
        get("/api/ops/inventory-transfers/"+incoming,source).okData();get("/api/ops/inventory-transfers/"+outgoing,source).okData();
        assertThat(post("/api/ops/inventory-transfers",header(a,b),source).status).isEqualTo(403);
        assertThat(post("/api/ops/inventory-transfers/"+hidden,header(a,b),source).status).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT from_ref FROM inv_transfer WHERE transfer_no=?",String.class,hidden)).isEqualTo(b);
    }
    @Test void shipAndReceiveUseTheirOwnWarehouseAndAllowRealCrossRegionHandover(){
        String t=transfer(a,b),cab=cabinet(a);items(t,cab);
        assertThat(post("/api/ops/inventory-transfers/"+t+"/ship",Map.of(),destination).status).isEqualTo(403);
        post("/api/ops/inventory-transfers/"+t+"/ship",Map.of(),source).okData();
        assertThat(post("/api/ops/inventory-transfers/"+t+"/receive",Map.of("receivedNos",List.of(cab)),source).status).isEqualTo(403);
        post("/api/ops/inventory-transfers/"+t+"/receive",Map.of("receivedNos",List.of(cab)),destination).okData();
        assertThat(jdbc.queryForMap("SELECT warehouse_no,region_id,status FROM dev_cabinet WHERE cabinet_no=?",cab))
                .containsEntry("warehouse_no",b).containsEntry("region_id","TRANSFER-B").containsEntry("status","IN_STOCK");
    }
    @Test void headerCannotLaunderADeviceFromAnotherPhysicalWarehouse(){
        String t=transfer(a,b),cab=cabinet(c);items(t,cab);
        assertThat(post("/api/ops/inventory-transfers/"+t+"/ship",Map.of(),source).status).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT status FROM inv_transfer WHERE transfer_no=?",String.class,t)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT warehouse_no FROM dev_cabinet WHERE cabinet_no=?",String.class,cab)).isEqualTo(c);
    }
    @Test void powerbankLeavesSourceStockOnceAndCannotShipOnTwoTransfers(){
        String pb="PBT"+tag;powerbanks.add(pb);
        jdbc.update("INSERT INTO dev_powerbank(powerbank_no,sn,vendor_code,status,qc_status,warehouse_no) VALUES (?,?,'TEST','IN_STOCK','PASSED',?)",pb,pb,a);
        List<String> pair=new ArrayList<>();
        for(int i=0;i<2;i++){
            var body=new java.util.HashMap<>(header(a,b));body.put("itemType","POWERBANK");
            String t=post("/api/ops/inventory-transfers",body,admin).okData().path("transferNo").asText();transfers.add(t);pair.add(t);
            post("/api/ops/inventory-transfers/"+t+"/items",Map.of("itemNos",List.of(pb)),source).okData();
        }
        post("/api/ops/inventory-transfers/"+pair.get(0)+"/ship",Map.of(),source).okData();
        assertThat(jdbc.queryForObject("SELECT warehouse_no FROM dev_powerbank WHERE powerbank_no=?",String.class,pb)).isNull();
        assertThat(get("/api/ops/inventory/stage-items?stage=IN_TRANSIT&itemType=POWERBANK",source).okData().path("items").findValuesAsText("itemNo")).contains(pb);
        assertThat(post("/api/ops/inventory-transfers/"+pair.get(1)+"/ship",Map.of(),source).status).isEqualTo(409);
        post("/api/ops/inventory-transfers/"+pair.get(0)+"/receive",Map.of("receivedNos",List.of(pb)),destination).okData();
        assertThat(jdbc.queryForObject("SELECT warehouse_no FROM dev_powerbank WHERE powerbank_no=?",String.class,pb)).isEqualTo(b);
        assertThat(get("/api/ops/inventory/stage-items?stage=IN_TRANSIT&itemType=POWERBANK",destination).okData().path("items").findValuesAsText("itemNo")).doesNotContain(pb);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dev_movement_log WHERE item_no=? AND event IN ('SHIP','RECEIVE')",Integer.class,pb)).isEqualTo(2);
    }
    @Test void locationEndpointsAuthorizeThroughTheirOwningSite() {
        String site="STT"+tag, location="LTT"+tag;
        jdbc.update("INSERT INTO loc_site(site_no,name,region_id) VALUES (?,?,'TRANSFER-A')",site,site);
        jdbc.update("INSERT INTO loc_location(location_no,site_no,name) VALUES (?,?,?)",location,site,location);
        try {
            var body=new java.util.HashMap<>(header(a,a));body.put("toType","LOCATION");body.put("toRef",location);
            String t=post("/api/ops/inventory-transfers",body,source).okData().path("transferNo").asText();transfers.add(t);
            get("/api/ops/inventory-transfers/"+t,source).okData();
            assertThat(get("/api/ops/inventory-transfers/"+t,destination).status).isEqualTo(403);
        } finally {jdbc.update("DELETE FROM loc_location WHERE location_no=?",location);jdbc.update("DELETE FROM loc_site WHERE site_no=?",site);}
    }
    @Test void missingPowerbankRemainsVisibleInTransitAfterPartialReceipt() {
        var body=new java.util.HashMap<>(header(a,b));body.put("itemType","POWERBANK");
        String t=post("/api/ops/inventory-transfers",body,admin).okData().path("transferNo").asText();transfers.add(t);
        for(int i=0;i<2;i++){
            String pb="PBM"+tag+i;powerbanks.add(pb);
            jdbc.update("INSERT INTO dev_powerbank(powerbank_no,sn,vendor_code,status,qc_status,warehouse_no) VALUES (?,?,'TEST','IN_STOCK','PASSED',?)",pb,pb,a);
        }
        post("/api/ops/inventory-transfers/"+t+"/items",Map.of("itemNos",powerbanks),source).okData();
        post("/api/ops/inventory-transfers/"+t+"/ship",Map.of(),source).okData();
        post("/api/ops/inventory-transfers/"+t+"/receive",Map.of("receivedNos",List.of(powerbanks.get(0))),destination).okData();
        assertThat(get("/api/ops/inventory/stage-items?stage=IN_TRANSIT&itemType=POWERBANK",destination).okData().path("items").findValuesAsText("itemNo"))
                .contains(powerbanks.get(1)).doesNotContain(powerbanks.get(0));
        jdbc.update("DELETE FROM inv_asset_diff WHERE source_ref=?",t);
    }
    @Test void powerbankTransferRejectsUnsupportedNonWarehouseDestination() {
        var body=new java.util.HashMap<>(header(a,b));body.put("itemType","POWERBANK");body.put("toType","SITE");
        var result=post("/api/ops/inventory-transfers",body,admin);
        if(result.status==200) transfers.add(result.okData().path("transferNo").asText());
        assertThat(result.status).isEqualTo(400);
    }

    @Test void historicalShortReceiptCannotAuthorizeALaterForeignShipment() {
        String d="WHTD"+tag,pb="PBH"+tag;powerbanks.add(pb);
        jdbc.update("INSERT INTO inv_warehouse(warehouse_no,name,region_id) VALUES (?,?,'TRANSFER-C')",d,d);
        try {
            jdbc.update("INSERT INTO dev_powerbank(powerbank_no,sn,vendor_code,status,qc_status,warehouse_no) VALUES (?,?,'TEST','IN_STOCK','PASSED',?)",pb,pb,a);
            var body=new java.util.HashMap<>(header(a,b));body.put("itemType","POWERBANK");
            String old=post("/api/ops/inventory-transfers",body,admin).okData().path("transferNo").asText();transfers.add(old);
            post("/api/ops/inventory-transfers/"+old+"/items",Map.of("itemNos",List.of(pb)),admin).okData();
            post("/api/ops/inventory-transfers/"+old+"/ship",Map.of(),admin).okData();
            // Recovered into C, then legitimately shipped in a new custody cycle.
            jdbc.update("UPDATE dev_powerbank SET warehouse_no=? WHERE powerbank_no=?",c,pb);
            body.put("fromRef",c);body.put("toRef",d);
            String next=post("/api/ops/inventory-transfers",body,admin).okData().path("transferNo").asText();transfers.add(next);
            post("/api/ops/inventory-transfers/"+next+"/items",Map.of("itemNos",List.of(pb)),admin).okData();
            post("/api/ops/inventory-transfers/"+next+"/ship",Map.of(),admin).okData();
            // A stale active receipt must not take the new shipment.
            assertThat(post("/api/ops/inventory-transfers/"+old+"/receive",Map.of("receivedNos",List.of(pb)),destination).status).isEqualTo(409);
            jdbc.update("UPDATE inv_transfer SET status='DONE' WHERE transfer_no=?",old);
            assertThat(get("/api/ops/inventory/stage-items?stage=IN_TRANSIT&itemType=POWERBANK",source).okData().path("items").findValuesAsText("itemNo")).doesNotContain(pb);
        } finally {jdbc.update("DELETE FROM inv_warehouse WHERE warehouse_no=?",d);}
    }

    @Test void receiptHoldsAssetLockAcrossShipmentAuthorizationAndWrite() throws Exception {
        String pb="PBC"+tag;powerbanks.add(pb);
        jdbc.update("INSERT INTO dev_powerbank(powerbank_no,sn,vendor_code,status,qc_status,warehouse_no) VALUES (?,?,'TEST','IN_STOCK','PASSED',?)",pb,pb,a);
        var body=new java.util.HashMap<>(header(a,b));body.put("itemType","POWERBANK");
        String t=post("/api/ops/inventory-transfers",body,admin).okData().path("transferNo").asText();transfers.add(t);
        post("/api/ops/inventory-transfers/"+t+"/items",Map.of("itemNos",List.of(pb)),admin).okData();
        post("/api/ops/inventory-transfers/"+t+"/ship",Map.of(),admin).okData();
        var checked=new java.util.concurrent.CountDownLatch(1);var resume=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call->{
            Object value=call.callRealMethod();checked.countDown();
            if(!resume.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("receipt resume timeout");
            return value;
        }).when(recorder).latestShipments(org.mockito.ArgumentMatchers.eq(List.of(pb)));
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var receiving=pool.submit(()->post("/api/ops/inventory-transfers/"+t+"/receive",Map.of("receivedNos",List.of(pb)),destination));
            assertThat(checked.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var competing=pool.submit(()->jdbc.update("UPDATE dev_powerbank SET warehouse_no=? WHERE powerbank_no=?",c,pb));
            try {
                assertThat(org.assertj.core.api.Assertions.catchThrowable(()->competing.get(300,java.util.concurrent.TimeUnit.MILLISECONDS)))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {resume.countDown();}
            receiving.get(10,java.util.concurrent.TimeUnit.SECONDS).okData();competing.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT warehouse_no FROM dev_powerbank WHERE powerbank_no=?",String.class,pb)).isEqualTo(c);
        } finally {resume.countDown();org.mockito.Mockito.reset(recorder);}
    }

    @Test void removalHoldsAssetLockAcrossOriginCheckAndShip() throws Exception {
        String cab=cabinet(null),wo="WOC"+tag;
        jdbc.update("UPDATE dev_cabinet SET status='DEPLOYED',site_no='SITE-OLD',issued_agent_no='AG-OLD',issued_at=NOW() WHERE cabinet_no=?",cab);
        lifecycle.undeploy(cab,"completed",wo);
        String t=ops.openReturn("SITE-OLD","old",cab,wo);transfers.add(t);
        var checked=new java.util.concurrent.CountDownLatch(1);var resume=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call->{
            Object value=call.callRealMethod();checked.countDown();
            if(!resume.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("ship resume timeout");
            return value;
        }).when(recorder).latestForUpdate("CABINET",cab);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var shipping=pool.submit(()->post("/api/ops/inventory-transfers/"+t+"/ship",Map.of(),admin));
            assertThat(checked.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var competing=pool.submit(()->post("/api/ops/devices/pool-return",Map.of("cabinetNos",List.of(cab),"warehouseNo",c,"reason","concurrent return"),admin));
            try {
                assertThat(org.assertj.core.api.Assertions.catchThrowable(()->competing.get(300,java.util.concurrent.TimeUnit.MILLISECONDS)))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {resume.countDown();}
            shipping.get(10,java.util.concurrent.TimeUnit.SECONDS).okData();
            assertThat(competing.get(10,java.util.concurrent.TimeUnit.SECONDS).status).isEqualTo(409);
        } finally {resume.countDown();org.mockito.Mockito.reset(recorder);}
    }

}
