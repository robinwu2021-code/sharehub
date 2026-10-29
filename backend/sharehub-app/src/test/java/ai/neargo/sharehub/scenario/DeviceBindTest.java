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
 * 绑定 / 解绑 / 撤机去向（V164；用户 2026-10-28：绑定的设备只从该商家的出库池里选，默认绑到默认站点的默认点位）。
 *
 * <p>守不变式 I2（点位商家 = 出库池商家）、I3（agent_no 只由点位派生）、I5（直营站点不经出库池），
 * 以及验收 AC3 / AC4 / AC7。每条用例新造商家与柜子。
 */
class DeviceBindTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
    private int seq;

    /** 运营代建 → 审核通过 → 补齐位置与行业；返回 {商家号, 默认站点号}。 */
    private String[] merchant() {
        String admin = login("ADMIN");
        Map<String, Object> b = new HashMap<>();
        b.put("phone", "+97154" + (1000000 + (int) (Math.abs(System.nanoTime()) % 8999999)));
        b.put("email", "bind" + System.nanoTime() + "@example.com");
        b.put("operatorName", "绑定测试商家 " + tag + (++seq));
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
        p.put("address", "Al Barsha");
        p.put("lng", 55.20);
        p.put("lat", 25.11);
        p.put("industry", "HOTEL");
        String site = post("/api/agent/agents/" + agentNo, p, admin).okData().path("defaultSiteNo").asText();
        return new String[]{agentNo, site};
    }

    private String cabinet() {
        String no = "CABB" + tag + (++seq);
        post("/api/ops/cabinets", Map.of("cabinetNo", no, "sn", no, "vendorCode", "TEST", "slotTotal", 8), login("ADMIN")).okData();
        jdbc.update("UPDATE dev_cabinet SET qc_status = 'PASSED', warehouse_no = 'WH-T' WHERE cabinet_no = ?", no);
        return no;
    }

    private String issued(String agentNo) {
        String c = cabinet();
        post("/api/ops/devices/issue", Map.of("agentNo", agentNo, "cabinetNos", List.of(c)), login("ADMIN")).okData();
        return c;
    }

    private Map<String, Object> row(String c) {
        return jdbc.queryForMap("SELECT location_no, site_no, agent_no, issued_agent_no, warehouse_no, status FROM dev_cabinet WHERE cabinet_no = ?", c);
    }

    private String defaultPoint(String siteNo) {
        return jdbc.queryForObject("SELECT location_no FROM loc_location WHERE site_no = ? AND is_default = 1 AND deleted = 0", String.class, siteNo);
    }

    /** 内部员工（运维角色），用员工号登录即带员工档案上的角色。 */
    private String employee() {
        Map<String, Object> m = new HashMap<>();
        m.put("employeeNo", "EMP_T" + System.nanoTime());
        m.put("name", "签收测试-" + tag);
        m.put("roleNo", "OPS");
        m.put("roleNos", List.of("OPS"));
        m.put("status", "ACTIVE");
        return post("/api/platform/employees", m, login("ADMIN")).okData().path("employeeNo").asText();
    }

    @Test
    @DisplayName("★★ 出库到站点 + 接收人（V195）：未签收不能绑；只有接收人本人能签；只能绑目的站点的点位；调整目的地后可绑别的站点")
    void issue_to_site_with_receiver_then_claim_and_bind() {
        String admin = login("ADMIN");
        String[] m = merchant();
        String site2 = post("/api/ops/sites", Map.of("name", "二号站 " + tag + (++seq), "agentNo", m[0],
                "address", "Mall of the Emirates", "lng", 55.2, "lat", 25.11), admin).okData()
                .path("siteNo").asText();
        String receiver = employee();
        String c = cabinet();
        post("/api/ops/devices/issue", Map.of("agentNo", m[0], "siteNo", site2, "receiverNo", receiver, "cabinetNos", List.of(c)),
                admin).okData();
        assertThat(jdbc.queryForMap("SELECT issued_site_no, issue_receiver_no, issue_claimed_at FROM dev_cabinet WHERE cabinet_no=?", c))
                .containsEntry("issued_site_no", site2).containsEntry("issue_receiver_no", receiver).containsEntry("issue_claimed_at", null);

        assertThat(post("/api/ops/devices/" + c + "/bind", Map.of(), admin).status).as("未签收不能绑（PB-D4）").isEqualTo(409);
        assertThat(post("/api/ops/devices/claim", Map.of("cabinetNos", List.of(c)), admin).status).as("别人不能代签").isEqualTo(409);
        String me = login("OPS", receiver, null);
        JsonNode mine = get("/api/ops/cabinets?page=1&size=20&pool=MINE", me).okData().path("list");
        assertThat(mine.toString()).as("待我签收").contains(c);
        post("/api/ops/devices/claim", Map.of("cabinetNos", List.of(c)), me).okData();

        assertThat(post("/api/ops/devices/" + c + "/bind", Map.of("locationNo", defaultPoint(m[1])), admin).status)
                .as("只能绑目的站点的点位（PB-D2）").isEqualTo(409);
        // 调整：改成只到商家（不指定站点、接收人不变 → 签收保留）→ 可绑商家默认站点
        assertThat(post("/api/ops/devices/issue-redirect", Map.of("cabinetNos", List.of(c), "agentNo", m[0], "receiverNo", receiver), admin)
                .status).as("调整原因必填").isEqualTo(400);
        post("/api/ops/devices/issue-redirect", Map.of("cabinetNos", List.of(c), "agentNo", m[0], "receiverNo", receiver,
                "reason", "站点写错"), admin).okData();
        post("/api/ops/devices/" + c + "/bind", Map.of("locationNo", defaultPoint(m[1])), admin).okData();
        assertThat(row(c).get("site_no")).isEqualTo(m[1]);
    }

    @Test
    @DisplayName("★★ 设备履历（V196，I8）：出库 → 签收 → 绑定 → 解绑 → 退回仓库，每个动作恰好一行，按序可查")
    void every_move_leaves_exactly_one_movement_row() {
        String admin = login("ADMIN");
        String[] m = merchant();
        String receiver = employee();
        String c = cabinet();
        post("/api/ops/devices/issue", Map.of("agentNo", m[0], "receiverNo", receiver, "cabinetNos", List.of(c)), admin).okData();
        post("/api/ops/devices/claim", Map.of("cabinetNos", List.of(c)), login("OPS", receiver, null)).okData();
        post("/api/ops/devices/" + c + "/bind", Map.of(), admin).okData();
        post("/api/ops/devices/" + c + "/unbind", Map.of("reason", "换点位"), admin).okData();
        post("/api/ops/devices/pool-return", Map.of("cabinetNos", List.of(c), "reason", "多发了"), admin).okData();
        // 失败的动作不留履历（与业务同事务）
        assertThat(post("/api/ops/devices/" + c + "/unbind", Map.of("reason", "x"), admin).status).isEqualTo(409);

        JsonNode rows = get("/api/ops/device-movements?itemNo=" + c, admin).okData();
        List<String> events = new java.util.ArrayList<>();
        rows.forEach(r -> events.add(0, r.path("event").asText()));
        assertThat(events).containsExactly("ISSUE", "CLAIM", "BIND", "UNBIND", "POOL_RETURN");
        JsonNode bind = rows.get(2);
        assertThat(bind.path("fromRef").asText()).isEqualTo("AGENT:" + m[0]);
        assertThat(bind.path("toRef").asText()).isEqualTo("LOC:" + defaultPoint(m[1]));
        assertThat(bind.path("agentAfter").asText()).isEqualTo(m[0]);
        assertThat(rows.get(0).path("toRef").asText()).isEqualTo("WH:WH001");
    }

    @Test
    @DisplayName("★ 出库到站点：不指定点位时默认绑该站点的默认点位；站点不属于该商家 → 409")
    void issue_to_site_defaults_to_that_sites_point() {
        String admin = login("ADMIN");
        String[] m = merchant(), other = merchant();
        String site2 = post("/api/ops/sites", Map.of("name", "二号站 " + tag + (++seq), "agentNo", m[0],
                "address", "Mall of the Emirates", "lng", 55.2, "lat", 25.11), admin).okData()
                .path("siteNo").asText();
        String c = cabinet();
        assertThat(post("/api/ops/devices/issue", Map.of("agentNo", m[0], "siteNo", other[1], "cabinetNos", List.of(c)), admin).status)
                .isEqualTo(409);
        post("/api/ops/devices/issue", Map.of("agentNo", m[0], "siteNo", site2, "cabinetNos", List.of(c)), admin).okData();
        post("/api/ops/devices/" + c + "/bind", Map.of(), admin).okData();
        assertThat(row(c).get("location_no")).isEqualTo(defaultPoint(site2));
    }

    @Test
    @DisplayName("★★ 不指定点位 → 绑到默认站点的默认点位；agent_no 随点位；可同事务开装机单（AC3）")
    void bind_defaults_to_the_default_point_and_opens_install() {
        String[] m = merchant();
        String c = issued(m[0]);
        JsonNode r = post("/api/ops/devices/" + c + "/bind", Map.of("openInstallWo", true), login("ADMIN")).okData();

        Map<String, Object> row = row(c);
        assertThat(row.get("location_no")).isEqualTo(defaultPoint(m[1]));
        assertThat(row.get("site_no")).isEqualTo(m[1]);
        assertThat(row.get("agent_no")).as("I3：agent_no 只由点位派生").isEqualTo(m[0]);
        String woNo = r.path("woNo").asText();
        assertThat(woNo).isNotBlank();
        assertThat(jdbc.queryForMap("SELECT type, cabinet_no, agent_no FROM wo_order WHERE wo_no = ?", woNo))
                .containsEntry("type", "INSTALL").containsEntry("cabinet_no", c).containsEntry("agent_no", m[0]);
    }

    @Test
    @DisplayName("★★ 出库给 A 的柜子绑到 B 的点位 → 409，什么都不改（AC4 / I2）")
    void cannot_bind_to_another_merchants_point() {
        String[] a = merchant(), b = merchant();
        String c = issued(a[0]);
        assertThat(post("/api/ops/devices/" + c + "/bind", Map.of("locationNo", defaultPoint(b[1])), login("ADMIN")).status)
                .isEqualTo(409);
        assertThat(row(c).get("location_no")).isNull();
    }

    @Test
    @DisplayName("★ 直营站点不经出库池：在仓的柜子可直接绑直营点位，绑定即离开仓库（I5）")
    void direct_site_binds_straight_from_the_warehouse() {
        String admin = login("ADMIN");
        String venue = get("/api/ops/venues?page=1&size=1", admin).okData().path("list").get(0).path("venueNo").asText();
        String site = post("/api/ops/sites", Map.of("name", "直营 " + tag, "venueNo", venue, "regionId", "Dubai Marina",
                "openHours", "10:00-22:00"), admin).okData().path("siteNo").asText();
        String c = cabinet();
        post("/api/ops/devices/" + c + "/bind", Map.of("locationNo", defaultPoint(site)), admin).okData();
        Map<String, Object> row = row(c);
        assertThat(row.get("agent_no")).isNull();
        assertThat(row.get("warehouse_no")).isNull();

        // 反过来：在仓、没出库给任何商家的柜子不能绑商家的点位
        String[] m = merchant();
        assertThat(post("/api/ops/devices/" + cabinet() + "/bind", Map.of("locationNo", defaultPoint(m[1])), admin).status)
                .isEqualTo(409);
    }

    @Test
    @DisplayName("★ 批量绑定一个事务：混进别家出库池的柜子，整批不动")
    void batch_bind_is_all_or_nothing() {
        String[] a = merchant(), b = merchant();
        String a1 = issued(a[0]), a2 = issued(a[0]), b1 = issued(b[0]);
        assertThat(post("/api/ops/devices/bind-batch", Map.of("agentNo", a[0], "deviceNos", List.of(a1, a2, b1)),
                login("ADMIN")).status).isEqualTo(409);
        assertThat(row(a1).get("location_no")).isNull();

        JsonNode ok = post("/api/ops/devices/bind-batch", Map.of("agentNo", a[0], "deviceNos", List.of(a1, a2)), login("ADMIN")).okData();
        assertThat(ok).hasSize(2);
    }

    @Test
    @DisplayName("★ 解绑：回同一商家出库池（agent_no 清空，出库池保留）；原因必填；没绑的不能解")
    void unbind_returns_to_the_same_pool() {
        String[] m = merchant();
        String c = issued(m[0]);
        String admin = login("ADMIN");
        post("/api/ops/devices/" + c + "/bind", Map.of(), admin).okData();

        assertThat(post("/api/ops/devices/" + c + "/unbind", Map.of(), admin).status).isEqualTo(400);
        post("/api/ops/devices/" + c + "/unbind", Map.of("reason", "点位选错"), admin).okData();
        Map<String, Object> row = row(c);
        assertThat(row.get("location_no")).isNull();
        assertThat(row.get("agent_no")).isNull();
        assertThat(row.get("issued_agent_no")).isEqualTo(m[0]);
        assertThat(post("/api/ops/devices/" + c + "/unbind", Map.of("reason", "再解一次"), admin).status).isEqualTo(409);
    }

    @Test
    @DisplayName("★★ 撤机去向「回出库池」：回到原商家出库池、不生成回仓单，可再绑第二个站点（AC7）")
    void removal_back_to_pool_can_be_rebound_elsewhere() {
        String[] m = merchant();
        String c = issued(m[0]);
        String admin = login("ADMIN");
        post("/api/ops/devices/" + c + "/bind", Map.of(), admin).okData();
        jdbc.update("UPDATE dev_cabinet SET status = 'DEPLOYED' WHERE cabinet_no = ?", c);

        String wo = post("/api/ops/work-orders", Map.of("type", "REMOVE", "cabinetNo", c, "description", "换到第二家",
                "disposition", "RETURN_TO_POOL"), admin).okData().path("woNo").asText();
        assertThat(jdbc.queryForObject("SELECT removal_disposition FROM wo_order WHERE wo_no = ?", String.class, wo))
                .isEqualTo("RETURN_TO_POOL");
        jdbc.update("UPDATE wo_order SET status = 'PROCESSING' WHERE wo_no = ?", wo);
        post("/api/ops/work-orders/" + wo + "/complete", Map.of("photos", "[\"remove.jpg\"]", "countedQty", 0), admin).okData();

        Map<String, Object> row = row(c);
        assertThat(row.get("status")).isEqualTo("IN_STOCK");
        assertThat(row.get("location_no")).isNull();
        assertThat(row.get("agent_no")).isNull();
        assertThat(row.get("issued_agent_no")).as("回出库池 = 留在原商家那里").isEqualTo(m[0]);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_transfer WHERE source_type = 'REMOVAL' AND source_ref = ?",
                Integer.class, wo)).as("回出库池不生成回仓单").isZero();

        String second = post("/api/ops/sites", Map.of("agentNo", m[0], "name", "第二家 " + tag,
                "address", "Dubai Mall", "lng", 55.28, "lat", 25.19), admin).okData().path("siteNo").asText();
        post("/api/ops/devices/" + c + "/bind", Map.of("locationNo", defaultPoint(second)), admin).okData();
        assertThat(row(c).get("site_no")).isEqualTo(second);
    }

    @Test
    @DisplayName("编辑接口不再改点位：带了不同的 locationNo → 400，去走绑定")
    void edit_no_longer_binds() {
        String[] m = merchant();
        String c = issued(m[0]);
        assertThat(post("/api/ops/cabinets/" + c, Map.of("locationNo", defaultPoint(m[1])), login("ADMIN")).status).isEqualTo(400);
    }
}
