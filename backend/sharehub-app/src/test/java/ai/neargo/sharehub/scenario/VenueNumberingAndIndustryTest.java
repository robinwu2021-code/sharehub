package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 场地管理三件事：编号服务端取、行业收成词表、地址带坐标。
 *
 * <h3>为什么这三件凑在一起</h3>
 * 它们是同一类毛病的三种形态 —— <b>界面上能填，填了不算数或者没人拦</b>：
 * <ul>
 *   <li><b>编号</b>：表单上有个「编号」框、占位写着「新增自动生成」，
 *       而后端 {@code createVenue} 第一行就是 {@code body.setVenueNo(null)} ——
 *       打进去的值被静默丢弃；</li>
 *   <li><b>行业</b>：自由文本框，生产 5 行里三行是错的
 *       （DXB Airports 填「地产」、Majid Al Futtaim 填「航空」）；</li>
 *   <li><b>地址</b>：商机有地址没坐标，签约转化传的是
 *       {@code SiteReq(..., address, null, null, ...)} —— 站点有地址没落点。</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VenueNumberingAndIndustryTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private String admin;
    private final List<String> venueNos = new ArrayList<>();
    private final List<String> leadNos = new ArrayList<>();

    @BeforeAll
    void setUp() {
        admin = login("ADMIN");
    }

    @AfterEach
    void clean() {
        for (String v : venueNos) jdbc.update("DELETE FROM loc_venue WHERE venue_no=?", v);
        for (String l : leadNos) jdbc.update("DELETE FROM loc_lead WHERE lead_no=?", l);
        /*
         * 按名字再兜一次。本类往共享测试库里写「预期会被拒」的行 ——
         * 一旦实现回退（负对照就是这么跑的），那一行会留下来，
         * 而下一次 every_stored_industry_is_in_the_vocabulary 会红在一个看上去毫不相关的地方。
         * 2026-09-26 实测：负对照跑完真的留了一行 VENMUI9X6VY1M3XM5DQ 在库里。
         */
        jdbc.update("DELETE FROM loc_venue WHERE name LIKE '【测试】%'");
        venueNos.clear();
        leadNos.clear();
    }

    // ───────────────────── 编号 ─────────────────────

    @Test
    @DisplayName("★ 编号是 VEN + 顺序号，且客户端传什么都不算数")
    void venue_no_is_server_assigned_and_sequential() {
        JsonNode a = createVenue(Map.of("name", "【测试】取号一"));
        String first = a.path("venueNo").asText();
        assertThat(first).as("形状应当是 VEN + 数字，而不是 ULID：%s", first).matches("^VEN\\d+$");

        JsonNode b = createVenue(Map.of("name", "【测试】取号二"));
        String second = b.path("venueNo").asText();
        assertThat(num(second)).as("第二个应当比第一个大 1（%s → %s）", first, second).isEqualTo(num(first) + 1);

        // 客户端硬塞一个编号：必须被忽略，而不是拿它建档
        JsonNode c = createVenue(Map.of("name", "【测试】硬塞编号", "venueNo", "VEN-I-TYPED-THIS"));
        assertThat(c.path("venueNo").asText()).as("传进来的编号被采纳了").matches("^VEN\\d+$");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM loc_venue WHERE venue_no=?",
                Integer.class, "VEN-I-TYPED-THIS")).isZero();
    }

    // ───────────────────── 行业 ─────────────────────

    @Test
    @DisplayName("★ 词表外的行业当场 400——存下去的话下拉选不中它，一保存又被改成别的值")
    void unknown_industry_is_rejected() {
        Resp r = post("/api/ops/venues", Map.of("name", "【测试】乱填行业", "industry", "随便写的"), admin);
        assertThat(r.status).as("%s", r.body).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM loc_venue WHERE name=?",
                Integer.class, "【测试】乱填行业")).as("拒了就不该留下半行").isZero();
    }

    @Test
    @DisplayName("词表内的行业存得进、读得回；空行业放行（「还没问清楚」是合法状态）")
    void known_industry_round_trips_and_blank_is_allowed() {
        JsonNode v = createVenue(Map.of("name", "【测试】行业正常", "industry", "SHOPPING_MALL"));
        assertThat(v.path("industry").asText()).isEqualTo("SHOPPING_MALL");

        JsonNode blank = createVenue(Map.of("name", "【测试】行业留空"));
        assertThat(blank.path("venueNo").asText()).isNotBlank();
    }

    @Test
    @DisplayName("★ 库里现存的行业值全在词表里——归一漏了哪一行，这条会点名它")
    void every_stored_industry_is_in_the_vocabulary() {
        /*
         * 这一条与 StoredValueInVocabularyTest 不重复：那一条按列注释比，
         * 而**注释和枚举是两份**，改了一边忘了另一边它抓不到。这里直接拿 Java 枚举比。
         */
        List<String> stored = jdbc.queryForList(
                "SELECT DISTINCT industry FROM loc_venue WHERE industry IS NOT NULL AND industry <> ''",
                String.class);
        assertThat(stored).as("前提：库里得有行业值，否则这条什么也没验").isNotEmpty();
        for (String s : stored) {
            assertThat(ai.neargo.sharehub.loc.VenueIndustry.of(s))
                    .as("loc_venue 里存着词表外的行业 '%s'", s).isPresent();
        }
    }

    // ───────────────────── 地址坐标 ─────────────────────

    @Test
    @DisplayName("★ 商机的坐标随地址一起带进转化出来的站点——此前那两个位置写死 null")
    void lead_coordinates_are_carried_into_the_converted_site() {
        String leadNo = createLead("【测试】带坐标的商机", "Dubai Marina Walk", "55.138000", "25.077000");

        Resp conv = post("/api/ops/leads/" + leadNo + "/convert", Map.of("openHours", "10:00-22:00"), admin);
        assertThat(conv.status).as("%s", conv.body).isEqualTo(200);
        String siteNo = conv.okData().path("siteNo").asText();

        Map<String, Object> site = jdbc.queryForMap(
                "SELECT address, lng, lat FROM loc_site WHERE site_no=?", siteNo);
        assertThat(String.valueOf(site.get("address"))).isEqualTo("Dubai Marina Walk");
        /*
         * 这两条是本用例的全部意义。原先它们必然是 null —— 站点有地址、地图上没落点，
         * C 端「找附近」按坐标排也排不到它，而这件事不报错。
         */
        assertThat(site.get("lng")).as("经度没带过去").isNotNull();
        assertThat(site.get("lat")).as("纬度没带过去").isNotNull();
        assertThat(new java.math.BigDecimal(String.valueOf(site.get("lng"))))
                .isEqualByComparingTo("55.138000");

        jdbc.update("DELETE FROM loc_site WHERE site_no=?", siteNo);
    }

    @Test
    @DisplayName("场地方的地址与坐标存得进、读得回")
    void venue_address_round_trips() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "【测试】带地址的场地方");
        body.put("address", "Sheikh Zayed Road");
        body.put("lng", "55.274000");
        body.put("lat", "25.197000");
        JsonNode v = createVenue(body);

        /*
         * 用 findInPages 而不是取一页自己找：共享测试库里场地方越攒越多，
         * 取一页的用例总有一天会红在「刚建的东西查不到」上 —— 看着像功能坏了，
         * 实际是分页没够着。这条规矩有卡口盯着（SinglePageSearchTest）。
         */
        JsonNode mine = findInPages("/api/ops/venues?keyword=带地址的场地方", "venueNo",
                v.path("venueNo").asText(), admin);
        assertThat(mine).as("刚建的场地方在列表里找不到").isNotNull();
        assertThat(mine.path("address").asText()).isEqualTo("Sheikh Zayed Road");
        assertThat(mine.path("lng").asDouble()).isEqualTo(55.274);
    }

    // ───────────────────── 夹具 ─────────────────────

    private JsonNode createVenue(Map<String, ?> body) {
        JsonNode v = post("/api/ops/venues", body, admin).okData();
        venueNos.add(v.path("venueNo").asText());
        return v;
    }

    private String createLead(String venueName, String address, String lng, String lat) {
        Map<String, Object> body = new HashMap<>();
        body.put("venueName", venueName);
        body.put("address", address);
        body.put("lng", lng);
        body.put("lat", lat);
        body.put("regionId", "R-TEST");   // 转化建站点时必填
        JsonNode l = post("/api/ops/leads", body, admin).okData();
        String no = l.path("leadNo").asText();
        leadNos.add(no);
        // 转化要求已签约
        jdbc.update("UPDATE loc_lead SET stage='SIGNED' WHERE lead_no=?", no);
        return no;
    }

    private static long num(String venueNo) {
        return Long.parseLong(venueNo.substring(3));
    }
}
