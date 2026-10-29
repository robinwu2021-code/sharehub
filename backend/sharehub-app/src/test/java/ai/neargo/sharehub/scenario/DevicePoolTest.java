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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 设备出库池（V161；用户 2026-10-28：绑定商家先出库，出库必须选商家）。
 *
 * <p>每条用例新造商家与柜子，只碰自己的数据。商家走运营代建 → 审核通过（BD 录入、管理员放行，四眼），
 * 需要「能接收设备」的再补位置与行业。
 */
class DevicePoolTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
    private int seq;

    private String merchant(boolean ready) {
        String admin = login("ADMIN");
        Map<String, Object> b = new HashMap<>();
        b.put("phone", "+97156" + (1000000 + (int) (Math.abs(System.nanoTime()) % 8999999)));
        b.put("email", "pool" + System.nanoTime() + "@example.com");
        b.put("operatorName", "出库池商家 " + tag + (++seq));
        b.put("operatorType", "AGENT");
        String applyNo = post("/api/agent/applies", b, login("BD")).okData().path("applyNo").asText();
        post("/api/agent/applies/" + applyNo + "/accept", Map.of(), admin).okData();
        String agentNo = post("/api/agent/applies/" + applyNo + "/audit", Map.of("approve", true), admin)
                .okData().path("operatorNo").asText();
        if (ready) {
            JsonNode cur = get("/api/agent/agents?page=1&size=5&keyword=" + agentNo, admin).okData().path("list").get(0);
            Map<String, Object> p = new HashMap<>();
            p.put("name", cur.path("name").asText());
            p.put("agentType", "AGENT");
            p.put("status", "ENABLED");
            p.put("address", "Dubai Hills Mall");
            p.put("lng", 55.24);
            p.put("lat", 25.10);
            p.put("industry", "RETAIL");
            post("/api/agent/agents/" + agentNo, p, admin).okData();
        }
        return agentNo;
    }

    /** 新柜：在库、质检放行、在仓 WH-T。 */
    private String cabinet() {
        String no = "CABP" + tag + (++seq);
        post("/api/ops/cabinets", Map.of("cabinetNo", no, "sn", no, "vendorCode", "TEST", "slotTotal", 8), login("ADMIN")).okData();
        jdbc.update("UPDATE dev_cabinet SET qc_status = 'PASSED', warehouse_no = 'WH-T' WHERE cabinet_no = ?", no);
        return no;
    }

    private Map<String, Object> row(String cabinetNo) {
        return jdbc.queryForMap("SELECT issued_agent_no, warehouse_no, agent_no, scope_agent_no FROM dev_cabinet WHERE cabinet_no = ?", cabinetNo);
    }

    private Resp issue(String agentNo, List<String> nos) {
        return post("/api/ops/devices/issue", Map.of("agentNo", agentNo, "cabinetNos", nos), login("ADMIN"));
    }

    @Test
    @DisplayName("★★ 出库给商家：离开仓库进入出库池，agent_no 不动，商家看得到（AC2）")
    void issue_moves_devices_into_the_merchants_pool() {
        String agentNo = merchant(true);
        String c1 = cabinet(), c2 = cabinet();

        JsonNode out = issue(agentNo, List.of(c1, c2)).okData();
        assertThat(out).hasSize(2);
        Map<String, Object> r = row(c1);
        assertThat(r.get("issued_agent_no")).isEqualTo(agentNo);
        assertThat(r.get("warehouse_no")).as("出库即离开仓库").isNull();
        assertThat(r.get("agent_no")).as("agent_no 只由绑定的点位派生（I3）").isNull();
        assertThat(r.get("scope_agent_no")).as("数据范围锚点落到出库池商家").isEqualTo(agentNo);

        JsonNode list = get("/api/ops/cabinets?page=1&size=50&issuedAgentNo=" + agentNo + "&pool=UNBOUND", login("ADMIN"))
                .okData().path("list");
        assertThat(list).extracting(n -> n.path("cabinetNo").asText()).containsExactlyInAnyOrder(c1, c2);

        JsonNode summary = get("/api/ops/inventory/issued-summary?agentNo=" + agentNo, login("ADMIN")).okData();
        assertThat(summary.get(0).path("unbound").asInt()).isEqualTo(2);

        // 商家自己登录：看得到自己出库池里待安装的柜子
        String merchantToken = login("AGENT", "pool-" + tag, agentNo);
        JsonNode mine = get("/api/ops/cabinets?page=1&size=50&pool=UNBOUND", merchantToken).okData().path("list");
        assertThat(mine).extracting(n -> n.path("cabinetNo").asText()).contains(c1, c2)
                .allSatisfy(no -> assertThat(row(no).get("scope_agent_no")).isEqualTo(agentNo));
    }

    @Test
    @DisplayName("★ 资料未补齐的商家不能接收设备（AC1）")
    void merchant_without_core_profile_is_refused() {
        String agentNo = merchant(false);
        String c = cabinet();
        assertThat(issue(agentNo, List.of(c)).status).isEqualTo(409);
        assertThat(row(c).get("issued_agent_no")).isNull();
    }

    @Test
    @DisplayName("★★ 整批一个事务：其中一台已在别人的出库池，整批不动")
    void a_batch_is_all_or_nothing() {
        String a = merchant(true), b = merchant(true);
        String taken = cabinet(), fresh = cabinet();
        issue(a, List.of(taken)).okData();

        assertThat(issue(b, List.of(fresh, taken)).status).isEqualTo(409);
        assertThat(row(fresh).get("issued_agent_no")).as("前面那台也不能出库").isNull();
        assertThat(row(taken).get("issued_agent_no")).as("不能被后来者覆盖").isEqualTo(a);
    }

    @Test
    @DisplayName("质检未放行的不能出库")
    void qc_pending_cannot_be_issued() {
        String agentNo = merchant(true);
        String c = cabinet();
        jdbc.update("UPDATE dev_cabinet SET qc_status = 'PENDING' WHERE cabinet_no = ?", c);
        assertThat(issue(agentNo, List.of(c)).status).isEqualTo(409);
    }

    @Test
    @DisplayName("★ 退回仓库：原因必填；退回后离开出库池、回到仓")
    void return_to_warehouse() {
        String agentNo = merchant(true);
        String c = cabinet();
        issue(agentNo, List.of(c)).okData();
        String admin = login("ADMIN");

        assertThat(post("/api/ops/devices/pool-return", Map.of("cabinetNos", List.of(c)), admin).status).isEqualTo(400);
        post("/api/ops/devices/pool-return", Map.of("cabinetNos", List.of(c), "warehouseNo", "WH002", "reason", "多出库了一台"), admin).okData();
        Map<String, Object> r = row(c);
        assertThat(r.get("issued_agent_no")).isNull();
        assertThat(r.get("warehouse_no")).isEqualTo("WH002");
        assertThat(post("/api/ops/devices/pool-return", Map.of("cabinetNos", List.of(c), "reason", "再退一次"), admin).status)
                .as("不在出库池的不能再退").isEqualTo(409);
    }

    @Test
    @DisplayName("★ 退回仓库不填仓 → 落到默认回仓仓（V182 种的迪拜仓），不再落到「不在任何仓」；填了不存在的仓 → 400（项目约定 notFound 为 400）")
    void return_to_warehouse_defaults_and_validates() {
        String agentNo = merchant(true);
        String c = cabinet();
        issue(agentNo, List.of(c)).okData();
        String admin = login("ADMIN");

        assertThat(post("/api/ops/devices/pool-return",
                Map.of("cabinetNos", List.of(c), "warehouseNo", "WH-NOPE", "reason", "填错仓"), admin).status)
                .as("仓号不存在").isEqualTo(400);
        assertThat(row(c).get("issued_agent_no")).as("被拒时不能半成功").isEqualTo(agentNo);

        post("/api/ops/devices/pool-return", Map.of("cabinetNos", List.of(c), "reason", "不填仓"), admin).okData();
        assertThat(row(c).get("warehouse_no")).as("默认回仓仓").isEqualTo("WH001");
    }

    @Test
    @DisplayName("仓库只读列表：四个酋长国各一个仓，迪拜仓标为默认回仓")
    void warehouses_are_listed_with_the_default_marked() {
        String admin = login("ADMIN");
        com.fasterxml.jackson.databind.JsonNode list = get("/api/ops/inventory/warehouses", admin).okData();
        List<String> nos = new java.util.ArrayList<>();
        list.forEach(w -> nos.add(w.path("warehouseNo").asText()));
        assertThat(nos).contains("WH001", "WH002", "WH003", "WH004");
        list.forEach(w -> assertThat(w.path("isDefault").asBoolean())
                .as(w.path("warehouseNo").asText() + " 的默认标记").isEqualTo("WH001".equals(w.path("warehouseNo").asText())));
    }

    @Test
    @DisplayName("★ I1：在仓与在出库池互斥由库兜底 —— 绕过服务直接写也写不进去")
    void warehouse_and_pool_are_mutually_exclusive_in_the_db() {
        String agentNo = merchant(true);
        String c = cabinet();
        issue(agentNo, List.of(c)).okData();
        assertThatThrownBy(() -> jdbc.update("UPDATE dev_cabinet SET warehouse_no = 'WH-T' WHERE cabinet_no = ?", c))
                // MySQL 报 3819（CHECK 违反），Spring 归不进 DataIntegrityViolation —— 按约束名断言
                .hasMessageContaining("chk_cab_pool_vs_wh");
    }
}
