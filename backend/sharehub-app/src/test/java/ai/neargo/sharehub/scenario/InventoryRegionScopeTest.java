package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class InventoryRegionScopeTest extends ApiTestSupport {
    @Autowired JdbcTemplate jdbc;

    @Test void warehouseDirectoryAndIntakeDocumentsRespectEmployeeRegion() {
        String tag = Long.toUnsignedString(System.nanoTime(), 36);
        String own="WHRA"+tag, other="WHRB"+tag, a="IKRA"+tag, b="IKRB"+tag;
        String ma="MDRA"+tag, mb="MDRB"+tag, mt="MDRT"+tag, da="DSRA"+tag, db="DSRB"+tag;
        String admin=login("ADMIN"), who="WR"+tag;
        for (String wh : new String[]{own, other})
            jdbc.update("INSERT INTO inv_warehouse(warehouse_no,name,region_id) VALUES (?,?,?)", wh, wh, wh.equals(own)?"REG-A":"REG-B");
        for (String no : new String[]{a,b})
            jdbc.update("INSERT INTO inv_intake(intake_no,kind,warehouse_no,status,arrived_at) VALUES (?,'OPENING',?,'OPEN',CURRENT_DATE)", no, no.equals(a)?own:other);
        jdbc.update("INSERT INTO inv_material_doc(doc_no,kind,purpose,warehouse_no,note) VALUES (?,'INBOUND','OPENING',?,'scope'),(?,'INBOUND','OPENING',?,'scope')",ma,own,mb,other);
        jdbc.update("INSERT INTO inv_material_doc(doc_no,kind,purpose,warehouse_no,to_warehouse_no,note) VALUES (?,'TRANSFER','TRANSFER',?,?,'scope')",mt,other,own);
        jdbc.update("INSERT INTO inv_disposal(disposal_no,kind,warehouse_no,note) VALUES (?,'SCRAP',?,'scope'),(?,'SCRAP',?,'scope')",da,own,db,other);
        try {
            String token=login("WAREHOUSE",who,null);
            put("/api/platform/data-scopes/EMPLOYEE/"+who, Map.of("scopeType","REGION","scopeRefs","REG-A"),admin).okData();
            var whs=get("/api/ops/inventory/warehouses",token).okData();
            assertThat(whs.findValuesAsText("warehouseNo")).contains(own).doesNotContain(other);
            var rows=get("/api/ops/inventory/intakes?keyword=IKR&page=1&size=200",token).okData().path("list");
            assertThat(rows.findValuesAsText("intakeNo")).contains(a).doesNotContain(b);
            get("/api/ops/inventory/intakes/"+a,token).okData();
            assertThat(get("/api/ops/inventory/intakes/"+b,token).status).isEqualTo(403);
            assertThat(get("/api/ops/inventory/material-docs?keyword=MDR",token).okData().path("list").findValuesAsText("docNo")).contains(ma).doesNotContain(mb);
            assertThat(get("/api/ops/inventory/material-docs/"+mb,token).status).isEqualTo(403);
            get("/api/ops/inventory/material-docs/"+mt,token).okData();
            assertThat(get("/api/ops/inventory/disposals?keyword=DSR",token).okData().path("list").findValuesAsText("disposalNo")).contains(da).doesNotContain(db);
            assertThat(get("/api/ops/inventory/disposals/"+db,token).status).isEqualTo(403);
            assertThat(post("/api/ops/inventory/intakes",Map.of("warehouseNo",other),token).status).isEqualTo(403);
            assertThat(post("/api/ops/inventory/intakes/"+b+"/inbound",Map.of("items",java.util.List.of()),token).status).isEqualTo(403);
            assertThat(post("/api/ops/inventory/intakes/"+b+"/dispose",Map.of(),token).status).isEqualTo(403);
            assertThat(post("/api/ops/inventory/intakes/"+b+"/items/CABINET/CAB-OTHER/qc",Map.of(),token).status).isEqualTo(403);
            assertThat(jdbc.queryForObject("SELECT status FROM inv_intake WHERE intake_no=?",String.class,b)).isEqualTo("OPEN");
            put("/api/platform/data-scopes/EMPLOYEE/"+who, Map.of("scopeType","SELF","scopeRefs",""),admin).okData();
            assertThat(get("/api/ops/inventory/warehouses",token).okData()).isEmpty();
            assertThat(get("/api/ops/inventory/intakes?keyword=IKR",token).okData().path("total").asInt()).isZero();
            assertThat(get("/api/ops/inventory/material-docs",token).okData().path("total").asInt()).isZero();
            assertThat(get("/api/ops/inventory/disposals",token).okData().path("total").asInt()).isZero();
        } finally {
            jdbc.update("DELETE FROM inv_intake WHERE intake_no IN (?,?)",a,b);
            jdbc.update("DELETE FROM inv_material_doc WHERE doc_no IN (?,?,?)",ma,mb,mt);
            jdbc.update("DELETE FROM inv_disposal WHERE disposal_no IN (?,?)",da,db);
            jdbc.update("DELETE FROM inv_warehouse WHERE warehouse_no IN (?,?)",own,other);
            put("/api/platform/data-scopes/EMPLOYEE/"+who,Map.of("scopeType","ALL","scopeRefs",""),admin).okData();
        }
    }
}
