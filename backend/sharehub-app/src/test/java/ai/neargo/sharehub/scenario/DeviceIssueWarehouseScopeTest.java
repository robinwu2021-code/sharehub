package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.FixtureCleanup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class DeviceIssueWarehouseScopeTest extends ApiTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Test void issueRequiresThePhysicalSourceWarehouseEvenWhenDeviceRegionIsVisible() {
        String tag=Long.toUnsignedString(System.nanoTime(),36);
        String a="WHIA"+tag,b="WHIB"+tag,c="CBIA"+tag,d="CBIB"+tag,agent="AGI"+tag,who="IS"+tag;
        String admin=login("ADMIN"),token=login("WAREHOUSE",who,null);
        jdbc.update("INSERT INTO inv_warehouse(warehouse_no,name,region_id) VALUES (?,?,'ISSUE-A'),(?,?,'ISSUE-B')",a,a,b,b);
        jdbc.update("INSERT INTO agt_agent(agent_no,name,agent_type,status,address,lng,lat,industry) VALUES (?,?,'AGENT','ENABLED','Test',55.2,25.1,'HOTEL')",agent,agent);
        for(String no:List.of(c,d)) jdbc.update("INSERT INTO dev_cabinet(cabinet_no,sn,vendor_code,status,qc_status,region_id,warehouse_no) VALUES (?,?,'TEST','IN_STOCK','PASSED','ISSUE-A',?)",no,no,no.equals(c)?a:b);
        try {
            put("/api/platform/data-scopes/EMPLOYEE/"+who,Map.of("scopeType","REGION","scopeRefs","ISSUE-A"),admin).okData();
            assertThat(post("/api/ops/devices/issue",Map.of("agentNo",agent,"cabinetNos",List.of(c,d)),token).status).isEqualTo(403);
            assertThat(jdbc.queryForObject("SELECT warehouse_no FROM dev_cabinet WHERE cabinet_no=?",String.class,c)).isEqualTo(a);
            assertThat(post("/api/ops/devices/issue",Map.of("agentNo",agent,"cabinetNos",List.of(d)),token).status).isEqualTo(403);
            assertThat(jdbc.queryForObject("SELECT warehouse_no FROM dev_cabinet WHERE cabinet_no=?",String.class,d)).isEqualTo(b);
            post("/api/ops/devices/issue",Map.of("agentNo",agent,"cabinetNos",List.of(c)),token).okData();
            assertThat(jdbc.queryForObject("SELECT issued_agent_no FROM dev_cabinet WHERE cabinet_no=?",String.class,c)).isEqualTo(agent);
        } finally {
            FixtureCleanup.dropCabinets(jdbc,List.of(c,d));
            jdbc.update("DELETE FROM agt_agent WHERE agent_no=?",agent);
            jdbc.update("DELETE FROM inv_warehouse WHERE warehouse_no IN (?,?)",a,b);
            put("/api/platform/data-scopes/EMPLOYEE/"+who,Map.of("scopeType","ALL","scopeRefs",""),admin).okData();
        }
    }
}
