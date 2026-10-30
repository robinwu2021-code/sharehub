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
 * 站点与点位（V162；用户 2026-10-28：商家可有多个站点、多个点位，一期由运营代建；绑定默认落默认站点的默认点位）。
 *
 * <p>守的是只在真库上看得出的事：建站与默认点位同事务、商家站点的归属与运维责任同时写、
 * 点位的商家由站点派生、每站至多一个默认点位由唯一键兜底。
 */
class SiteAndPointTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
    private int seq;

    /** 运营代建 → 审核通过（BD 录入、管理员放行），并补齐位置与行业 —— 返回商家号与它的默认站点。 */
    private String[] readyMerchant() {
        String admin = login("ADMIN");
        Map<String, Object> b = new HashMap<>();
        b.put("phone", "+97152" + (1000000 + (int) (Math.abs(System.nanoTime()) % 8999999)));
        b.put("email", "site" + System.nanoTime() + "@example.com");
        b.put("operatorName", "站点测试商家 " + tag + (++seq));
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
        p.put("address", "City Walk");
        p.put("lng", 55.26);
        p.put("lat", 25.20);
        p.put("industry", "F_AND_B");
        String defaultSite = post("/api/agent/agents/" + agentNo, p, admin).okData().path("defaultSiteNo").asText();
        return new String[]{agentNo, defaultSite};
    }

    private List<Map<String, Object>> defaultsOf(String siteNo) {
        return jdbc.queryForList("SELECT location_no, agent_no, site_name FROM loc_location "
                + "WHERE site_no = ? AND is_default = 1 AND deleted = 0", siteNo);
    }

    @Test
    @DisplayName("★★ 商家默认站点也带默认点位，点位商家随站点")
    void merchant_default_site_has_a_default_point() {
        String[] m = readyMerchant();
        List<Map<String, Object>> d = defaultsOf(m[1]);
        assertThat(d).hasSize(1);
        assertThat(d.get(0).get("agent_no")).as("划拨级联到点位").isEqualTo(m[0]);
    }

    @Test
    @DisplayName("★★ 运营为商家新建站点：同事务写归属、运维责任、默认点位；场地方与营业时间可空（AC5）")
    void ops_creates_a_second_site_for_a_merchant() {
        String[] m = readyMerchant();
        String admin = login("ADMIN");

        assertThat(post("/api/ops/sites", Map.of("agentNo", m[0], "name", "没给位置"), admin).status)
                .as("站点上 C 端地图，没坐标不建").isEqualTo(400);

        JsonNode site = post("/api/ops/sites", Map.of("agentNo", m[0], "name", "第二家 " + tag,
                "address", "Mall of the Emirates", "lng", 55.2, "lat", 25.11), admin).okData();
        String siteNo = site.path("siteNo").asText();
        assertThat(site.path("agentNo").asText()).isEqualTo(m[0]);
        assertThat(site.path("venueNo").isNull()).as("一期场地方只记录，商家站点不挂").isTrue();
        assertThat(site.path("defaultLocationNo").asText()).isNotBlank();
        assertThat(defaultsOf(siteNo)).hasSize(1).allSatisfy(r -> assertThat(r.get("agent_no")).isEqualTo(m[0]));
        assertThat(jdbc.queryForList("SELECT role FROM loc_site_agent WHERE site_no = ? AND agent_no = ? AND deleted = 0",
                String.class, siteNo, m[0])).as("分润读责任行").containsExactly("OPERATE");

        JsonNode mine = get("/api/ops/sites?page=1&size=20&agentNo=" + m[0], admin).okData().path("list");
        assertThat(mine).extracting(n -> n.path("siteNo").asText()).containsExactlyInAnyOrder(m[1], siteNo);
    }

    @Test
    @DisplayName("★ 直营建站同样带默认点位")
    void direct_site_also_gets_a_default_point() {
        String admin = login("ADMIN");
        String venue = get("/api/ops/venues?page=1&size=1", admin).okData().path("list").get(0).path("venueNo").asText();
        String siteNo = post("/api/ops/sites", Map.of("name", "直营 " + tag, "venueNo", venue, "regionId", "Dubai Marina",
                "openHours", "10:00-22:00"), admin).okData().path("siteNo").asText();
        assertThat(defaultsOf(siteNo)).hasSize(1).allSatisfy(r -> assertThat(r.get("agent_no")).isNull());
    }

    @Test
    @DisplayName("★★ 新建点位：编号服务端取、站点须存在、商家与站点名随站点；点位不能换站点")
    void save_point_derives_from_the_site() {
        String[] m = readyMerchant();
        String admin = login("ADMIN");

        JsonNode p = post("/api/ops/locations", Map.of("name", "二楼扶梯口", "siteNo", m[1],
                "locationNo", "LOC-CLIENT-PICKED", "siteName", "客户端乱填的站点名"), admin).okData();
        String no = p.path("locationNo").asText();
        assertThat(no).as("请求体里的编号不作数").startsWith("LOC").isNotEqualTo("LOC-CLIENT-PICKED");
        assertThat(p.path("agentNo").asText()).as("此前新建点位从不写商家，商家看不到自己的新点位").isEqualTo(m[0]);
        assertThat(p.path("siteName").asText()).isNotEqualTo("客户端乱填的站点名");
        assertThat(p.path("isDefault").asBoolean()).isFalse();

        // 本仓「查无此记录」统一回 400（BizException.notFound 的约定）
        assertThat(post("/api/ops/locations", Map.of("name", "x", "siteNo", "ST-NOT-EXIST"), admin).status).isEqualTo(400);
        assertThat(post("/api/ops/locations/LOC-NOT-EXIST", Map.of("name", "x", "siteNo", m[1]), admin).status)
                .as("路径带号却查不到 = 查无此记录，不顺手新建").isEqualTo(400);
        String other = post("/api/ops/sites", Map.of("agentNo", m[0], "name", "另一家 " + tag,
                "address", "Ibn Battuta", "lng", 55.11, "lat", 25.04), admin).okData().path("siteNo").asText();
        assertThat(post("/api/ops/locations/" + no, Map.of("name", "换站点", "siteNo", other), admin).status)
                .as("点位不能换站点").isEqualTo(409);

        JsonNode list = get("/api/ops/locations?page=1&size=20&siteNo=" + m[1], admin).okData().path("list");
        assertThat(list.get(0).path("isDefault").asBoolean()).as("默认点位排最前").isTrue();
    }

    @Test
    @DisplayName("★ 每站至多一个默认点位：绕过服务直接写也写不进去")
    void at_most_one_default_point_per_site() {
        String[] m = readyMerchant();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO loc_location (location_no, tenant_id, site_no, name, status, is_default) "
                + "VALUES (?, 'MAIN', ?, '第二个默认', 'ACTIVE', 1)", "LOCX" + tag, m[1]))
                .hasMessageContaining("uk_loc_default");
    }
}
