package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 整站换商家（技术设计 §3.7，验收 AC9）。
 *
 * <p>守的是「谁看得到」与「谁分到钱」不分开：站点、点位、机柜的商家号，机柜的出库池商家，
 * 站点的 OPERATE 责任行 —— 同一次划拨里一起变。
 */
class WholeSiteReassignTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
    private int seq;

    private String[] merchant() {
        String admin = login("ADMIN");
        Map<String, Object> b = new HashMap<>();
        b.put("phone", "+97153" + (1000000 + (int) (Math.abs(System.nanoTime()) % 8999999)));
        b.put("email", "move" + System.nanoTime() + "@example.com");
        b.put("operatorName", "换商家测试 " + tag + (++seq));
        b.put("operatorType", "AGENT");
        String applyNo = post("/api/agent/applies", b, login("BD")).okData().path("applyNo").asText();
        post("/api/agent/applies/" + applyNo + "/accept", Map.of(), admin).okData();
        String agentNo = post("/api/agent/applies/" + applyNo + "/audit", Map.of("approve", true), admin)
                .okData().path("operatorNo").asText();
        JsonNode cur = get("/api/agent/agents?page=1&size=5&keyword=" + agentNo, admin).okData().path("list").get(0);
        Map<String, Object> p = new HashMap<>();
        p.put("name", cur.path("name").asText());
        p.put("agentType", "AGENT");
        p.put("status", "ENABLED");
        p.put("address", "Jumeirah");
        p.put("lng", 55.23);
        p.put("lat", 25.21);
        p.put("industry", "RETAIL");
        String site = post("/api/agent/agents/" + agentNo, p, admin).okData().path("defaultSiteNo").asText();
        return new String[]{agentNo, site};
    }

    /** 出库给商家并绑到它的默认点位。 */
    private String boundCabinet(String agentNo) {
        String no = "CABM" + tag + (++seq);
        String admin = login("ADMIN");
        post("/api/ops/cabinets", Map.of("cabinetNo", no, "sn", no, "vendorCode", "TEST", "slotTotal", 8), admin).okData();
        jdbc.update("UPDATE dev_cabinet SET qc_status = 'PASSED', warehouse_no = 'WH-T' WHERE cabinet_no = ?", no);
        post("/api/ops/devices/issue", Map.of("agentNo", agentNo, "cabinetNos", List.of(no)), admin).okData();
        post("/api/ops/devices/" + no + "/bind", Map.of(), admin).okData();
        return no;
    }

    private Map<String, String> operateRows(String siteNo) {
        Map<String, String> out = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT agent_no, effective_to FROM loc_site_agent "
                + "WHERE site_no = ? AND role = 'OPERATE' AND deleted = 0", siteNo)) {
            out.put((String) r.get("agent_no"), r.get("effective_to") == null ? "ACTIVE" : "ENDED");
        }
        return out;
    }

    private Resp assign(String agentNo, String siteNo, String action) {
        return post("/api/agent/assignments", Map.of("agentNo", agentNo, "targetType", "SITE", "targetNo", siteNo,
                "action", action), login("ADMIN"));
    }

    @Test
    @DisplayName("★★ 整站 A → C：站点、点位、柜子、出库池、责任行一起变（AC9）")
    void whole_site_moves_everything_together() {
        String[] a = merchant(), c = merchant();
        String cab = boundCabinet(a[0]);

        assign(c[0], a[1], "ASSIGN").okData();

        assertThat(jdbc.queryForObject("SELECT agent_no FROM loc_site WHERE site_no = ?", String.class, a[1])).isEqualTo(c[0]);
        assertThat(jdbc.queryForList("SELECT DISTINCT agent_no FROM loc_location WHERE site_no = ? AND deleted = 0",
                String.class, a[1])).containsExactly(c[0]);
        Map<String, Object> row = jdbc.queryForMap("SELECT agent_no, issued_agent_no FROM dev_cabinet WHERE cabinet_no = ?", cab);
        assertThat(row.get("agent_no")).isEqualTo(c[0]);
        assertThat(row.get("issued_agent_no")).as("出库池随站点转给新商家，否则 I2 被打破").isEqualTo(c[0]);
        assertThat(operateRows(a[1])).as("旧商家那行结束、新商家开一行")
                .containsEntry(a[0], "ENDED").containsEntry(c[0], "ACTIVE");

        // 再换回 A：重开 A 那一行，而不是撞唯一键
        assign(a[0], a[1], "ASSIGN").okData();
        assertThat(operateRows(a[1])).containsEntry(a[0], "ACTIVE").containsEntry(c[0], "ENDED");
    }

    @Test
    @DisplayName("★ 收回为直营：商家清空、柜子离开出库池（I5）、责任行只结束不新开")
    void revoke_to_direct_operation() {
        String[] a = merchant();
        String cab = boundCabinet(a[0]);
        assign(a[0], a[1], "REVOKE").okData();
        Map<String, Object> row = jdbc.queryForMap("SELECT agent_no, issued_agent_no FROM dev_cabinet WHERE cabinet_no = ?", cab);
        assertThat(row.get("agent_no")).isNull();
        assertThat(row.get("issued_agent_no")).isNull();
        assertThat(operateRows(a[1])).containsEntry(a[0], "ENDED").hasSize(1);
    }

    @Test
    @DisplayName("★ 只剩整站：按机柜 / 按点位划拨 400")
    void cabinet_and_location_granularity_are_retired() {
        String[] a = merchant(), c = merchant();
        String cab = boundCabinet(a[0]);
        String admin = login("ADMIN");
        assertThat(post("/api/agent/assignments", Map.of("agentNo", c[0], "targetType", "CABINET", "targetNo", cab,
                "action", "ASSIGN"), admin).status).isEqualTo(400);
        String loc = jdbc.queryForObject("SELECT location_no FROM dev_cabinet WHERE cabinet_no = ?", String.class, cab);
        assertThat(post("/api/agent/assignments", Map.of("agentNo", c[0], "targetType", "LOCATION", "targetNo", loc,
                "action", "ASSIGN"), admin).status).isEqualTo(400);
    }

    @Test
    @DisplayName("★ 批量整站换商家一个事务：其中一个站点不存在，整批不动")
    void batch_is_all_or_nothing() {
        String[] a = merchant(), c = merchant();
        String admin = login("ADMIN");
        assertThat(post("/api/agent/assignments/batch", Map.of("agentNo", c[0], "siteNos", List.of(a[1], "ST-NOT-EXIST")),
                admin).status).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT agent_no FROM loc_site WHERE site_no = ?", String.class, a[1])).isEqualTo(a[0]);

        assertThat(post("/api/agent/assignments/batch", Map.of("agentNo", c[0], "siteNos", List.of(a[1])), admin)
                .okData()).hasSize(1);
    }
}
