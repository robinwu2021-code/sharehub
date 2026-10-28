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
 * 商家核心资料与默认站点（V157；需求方案 D4 / D6 / D11，验收 AC-03 / AC-04）。
 *
 * <p>每条用例走一遍运营代建 → 审核通过，拿一个全新的商家，不碰种子商家。
 *
 * <p>守的是这几件只在真库上看得出的事：
 * <ul>
 *   <li>位置补齐的那一刻建站，且归属（数据范围读的 agent_no）与运维责任（分润读的责任行）同时写入 ——
 *       只写一份，「谁看得到」与「谁分到钱」就会分开，而且不报错；</li>
 *   <li>再次保存不重复建站；位置变了只同步地址坐标，站点号不变；</li>
 *   <li>位置、行业不齐时划拨被拦。</li>
 * </ul>
 */
class Phase1MerchantProfileTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    /** 运营代建 → 受理 → 另一人通过，返回新商家编号。 */
    private String newMerchant(String admin, String name) {
        Map<String, Object> b = new HashMap<>();
        b.put("phone", "+97155" + (1000000 + (int) (Math.abs(System.nanoTime()) % 8999999)));
        b.put("email", "m" + System.nanoTime() + "@example.com");
        b.put("operatorName", name);
        b.put("operatorType", "AGENT");
        // 四眼：录入人不能自己放行（ApplyServiceImpl）—— BD 录入，管理员受理并放行
        String applyNo = post("/api/agent/applies", b, login("BD")).okData().path("applyNo").asText();
        post("/api/agent/applies/" + applyNo + "/accept", Map.of(), admin).okData();
        return post("/api/agent/applies/" + applyNo + "/audit", Map.of("approve", true), admin)
                .okData().path("operatorNo").asText();
    }

    private Resp saveProfile(String admin, String agentNo, Map<String, Object> extra) {
        JsonNode cur = get("/api/agent/agents?page=1&size=5&keyword=" + agentNo, admin).okData().path("list").get(0);
        Map<String, Object> b = new HashMap<>();
        b.put("name", cur.path("name").asText());
        b.put("agentType", cur.path("agentType").asText());
        b.put("status", cur.path("status").asText());
        b.put("contactName", "联系人");
        b.putAll(extra);
        return post("/api/agent/agents/" + agentNo, b, admin);
    }

    private static Map<String, Object> location(String address, double lng, double lat) {
        return Map.of("address", address, "lng", lng, "lat", lat);
    }

    @Test
    @DisplayName("★★ 位置与行业不齐时划拨被拦，且拦在落流水之前（AC-04）")
    void assigning_to_a_merchant_without_core_profile_is_refused() {
        String admin = login("ADMIN");
        String agentNo = newMerchant(admin, "门禁测试商家");

        Resp r = post("/api/agent/assignments", Map.of("agentNo", agentNo, "targetType", "SITE",
                "targetNo", "ST300", "action", "ASSIGN"), admin);
        assertThat(r.status).as("缺位置与行业时不能划入资产").isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agt_assignment WHERE agent_no = ?", Integer.class, agentNo))
                .as("被拦的划拨不能留下流水").isZero();

        // 只补位置、不补行业：仍然拦
        saveProfile(admin, agentNo, location("Dubai Marina Walk", 55.138, 25.080)).okData();
        assertThat(post("/api/agent/assignments", Map.of("agentNo", agentNo, "targetType", "SITE",
                "targetNo", "ST300", "action", "ASSIGN"), admin).status).isEqualTo(400);
    }

    @Test
    @DisplayName("★★ 补齐位置即建默认站点，归属与运维责任同时写入；重复保存不重复建站（AC-03）")
    void completing_location_provisions_one_default_site_with_owner_and_operate_role() {
        String admin = login("ADMIN");
        String agentNo = newMerchant(admin, "默认站点测试商家");

        Map<String, Object> first = new HashMap<>(location("JBR The Walk", 55.1352, 25.0779));
        first.put("industry", "HOTEL");
        JsonNode saved = saveProfile(admin, agentNo, first).okData();
        String siteNo = saved.path("defaultSiteNo").asText();
        assertThat(siteNo).as("补齐位置后应回写默认站点").isNotBlank();
        assertThat(saved.path("industry").asText()).isEqualTo("HOTEL");

        Map<String, Object> site = jdbc.queryForMap(
                "SELECT name, address, lng, lat, agent_no, venue_no FROM loc_site WHERE site_no = ?", siteNo);
        assertThat(site.get("name")).as("站点名取店名").isEqualTo("默认站点测试商家");
        assertThat(site.get("address")).isEqualTo("JBR The Walk");
        assertThat(new java.math.BigDecimal(String.valueOf(site.get("lng")))).isEqualByComparingTo("55.1352");
        assertThat(site.get("agent_no")).as("数据范围读这一列 —— 必须是这个商家").isEqualTo(agentNo);
        assertThat(site.get("venue_no")).as("一期场地方只是记录，默认站点不挂场地方").isNull();

        List<String> roles = jdbc.queryForList(
                "SELECT role FROM loc_site_agent WHERE site_no = ? AND agent_no = ? AND deleted = 0",
                String.class, siteNo, agentNo);
        assertThat(roles).as("分润读责任行 —— 只写归属不写责任，钱会分给别人").containsExactly("OPERATE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agt_assignment WHERE agent_no = ? AND target_no = ?",
                Integer.class, agentNo, siteNo)).as("归属变更要有流水").isEqualTo(1);

        // 同样的位置再存一次：不建第二个站点
        assertThat(saveProfile(admin, agentNo, first).okData().path("defaultSiteNo").asText()).isEqualTo(siteNo);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM loc_site WHERE agent_no = ?", Integer.class, agentNo))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★ 位置变更只同步站点地址与坐标，站点号与归属不变")
    void moving_the_location_updates_the_same_site() {
        String admin = login("ADMIN");
        String agentNo = newMerchant(admin, "搬迁测试商家");
        Map<String, Object> first = new HashMap<>(location("Old Town", 55.27, 25.19));
        first.put("industry", "F_AND_B");
        String siteNo = saveProfile(admin, agentNo, first).okData().path("defaultSiteNo").asText();

        saveProfile(admin, agentNo, location("Business Bay Tower 2", 55.2655, 25.1866)).okData();

        Map<String, Object> site = jdbc.queryForMap("SELECT address, lat, agent_no FROM loc_site WHERE site_no = ?", siteNo);
        assertThat(site.get("address")).isEqualTo("Business Bay Tower 2");
        assertThat(new java.math.BigDecimal(String.valueOf(site.get("lat")))).isEqualByComparingTo("25.1866");
        assertThat(site.get("agent_no")).isEqualTo(agentNo);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agt_assignment WHERE agent_no = ?", Integer.class, agentNo))
                .as("搬迁不是重新划拨").isEqualTo(1);
    }

    @Test
    @DisplayName("★ 只给地址不给坐标、行业不在词表：当场拒，不落半截数据")
    void partial_location_and_unknown_industry_are_rejected() {
        String admin = login("ADMIN");
        String agentNo = newMerchant(admin, "校验测试商家");

        assertThat(saveProfile(admin, agentNo, Map.of("address", "只有地址")).status)
                .as("没有坐标建出来的站点在地图上没有落点").isEqualTo(400);
        assertThat(saveProfile(admin, agentNo, Map.of("industry", "SPACESHIP")).status).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM loc_site WHERE agent_no = ?", Integer.class, agentNo)).isZero();
    }

    @Test
    @DisplayName("只改联系人的保存不清空已填的位置与行业（不传 = 不改）")
    void saving_without_location_keeps_it() {
        String admin = login("ADMIN");
        String agentNo = newMerchant(admin, "保留测试商家");
        Map<String, Object> first = new HashMap<>(location("Al Wasl Rd", 55.23, 25.17));
        first.put("industry", "RETAIL");
        saveProfile(admin, agentNo, first).okData();

        JsonNode after = saveProfile(admin, agentNo, Map.of("contactName", "换了联系人")).okData();
        assertThat(after.path("address").asText()).isEqualTo("Al Wasl Rd");
        assertThat(after.path("industry").asText()).isEqualTo("RETAIL");
    }
}
