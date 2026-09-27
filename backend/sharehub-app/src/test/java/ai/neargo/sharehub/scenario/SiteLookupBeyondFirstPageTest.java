package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「拿分页当查询」—— 超过第一页的那部分数据**静默消失**。
 *
 * <p>{@code MpNearbyController} 里有三处这个形状：
 * <ul>
 *   <li>门店详情按 {@code siteNo} 找站点，做法是 {@code loc.pageSites(1, 200, …)} 再过滤 ——
 *       站点超过 200 个时，第 201 家起查详情报「门店不存在」。<b>真实存在的店说不存在</b>；</li>
 *   <li>收藏列表同样从 {@code pageSites(1, 200)} 里挑，收藏了靠后的店就是不出现；</li>
 *   <li>「我收藏了吗」用 {@code favorites.pageByOwner(me, 1, 200)} 再 contains ——
 *       收藏超过 200 家后，心形永远不亮；</li>
 *   <li><b>后果最重的一处</b>：借出确认页按机柜反查站点，也是从那一页里挑。
 *       站点落在第一页之外就返 {@code null}，报价退回兜底值 ——
 *       <b>确认页给出的是错的价格</b>，而用户点下去就按那个价借了。</li>
 * </ul>
 *
 * <p>三处的共同点：<b>200 这个数是分页默认值，不是业务上限</b>，
 * 而越界的表现全是「查不到 / 不显示」，没有一处会报错。
 * 13 个站点的测试库与今天的生产都盖不住它 —— 所以要用数据把边界顶出来。
 *
 * <p>正确做法是按键单查（{@code siteOf} / {@code isFavorite}），
 * 而不是把上限调大：调大只是把同一个洞推远一点。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SiteLookupBeyondFirstPageTest extends ApiTestSupport {

    /** 顶到 200 之外就够了，灌太多只是让用例变慢。 */
    private static final int FILLER = 210;

    @Autowired
    JdbcTemplate jdbc;

    final List<String> sites = new ArrayList<>();
    final List<String> cabinets = new ArrayList<>();
    final List<String> users = new ArrayList<>();

    @AfterAll
    void cleanup() {
        for (String u : users) jdbc.update("DELETE FROM usr_favorite WHERE c_user_no=?", u);
        ai.neargo.sharehub.support.FixtureCleanup.dropCabinets(jdbc, cabinets);
        // 按前缀一把删，逐条删 210 行没必要
        jdbc.update("DELETE FROM loc_site WHERE site_no LIKE 'STPG%'");
    }

    @Test
    @DisplayName("站点超过 200 个：靠后的门店详情仍查得到，且能收藏")
    void siteBeyondTheFirstPageIsStillReachable() {
        String venue = jdbc.queryForObject(
                "SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);

        // pageSites 按 id 升序 → 后插的排在最后，正好落在第一页之外
        String last = null;
        for (int i = 0; i < FILLER; i++) {
            String no = "STPG" + rnd();
            last = no;
            jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours, region_id)"
                    + " VALUES (?, 'MAIN', ?, ?, 'ACTIVE', '00:00-23:59', 'R-TEST')", no, venue, "分页边界测试站点 " + no);
        }
        sites.add(last);
        /*
         * 收藏夹返的是**门店卡片**，算不出报价的店会被正确地跳过（一家店算不出价
         * 不该让整个收藏夹空掉）。所以这家店要有合同与机柜，否则本例证明的是
         * 「没价的店不出现」，而那是对的行为，不是缺陷。
         */
        jdbc.update("INSERT INTO loc_contract (contract_no, tenant_id, venue_no, site_no, share_rate, start_at, end_at, status)"
                        + " VALUES (?, 'MAIN', ?, ?, 0.2, ?, ?, 'ACTIVE')",
                "CTPG" + rnd(), venue, last, bizToday().minusDays(1).toString(), bizToday().plusYears(1).toString());
        String favCab = "CBPG" + rnd();
        cabinets.add(favCab);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, site_no, slot_total,"
                        + " available_count, status, online_status, last_heartbeat_at)"
                        + " VALUES (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, 8, 3, 'DEPLOYED', 'ONLINE', NOW(3))",
                favCab, favCab, last);

        int total = jdbc.queryForObject("SELECT COUNT(*) FROM loc_site WHERE archived_at IS NULL", Integer.class);
        assertThat(total).as("本例要求站点总数超过 200，否则证明不了任何事").isGreaterThan(200);

        var resp = get("/mp/sites/" + last, null);
        assertThat(resp.code())
                .as("第 %d 家站点的详情必须查得到 —— 「拿分页当查询」时这里报「门店不存在」（响应：%s）",
                        total, resp.msg())
                .isZero();
        assertThat(resp.okData().path("siteNo").asText()).isEqualTo(last);

        // 收藏这一家，再看两处「已收藏」判定
        String phone = "+9715" + (10000000 + new java.util.Random().nextInt(89999999));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        JsonNode login = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null).okData();
        String token = login.path("token").asText();
        users.add(login.path("cUserNo").asText());

        assertThat(post("/mp/user/favorites/" + last, Map.of(), token).okData().asBoolean())
                .as("切换后应为已收藏").isTrue();

        assertThat(get("/mp/sites/" + last, token).okData().path("favorite").asBoolean())
                .as("门店详情的「已收藏」要认得出来 —— 从 pageByOwner(1,200) 里 contains 的话，"
                        + "收藏靠后的店时这一位恒为 false").isTrue();

        List<String> fav = new ArrayList<>();
        for (JsonNode c : get("/mp/user/favorites", token).okData()) fav.add(c.path("siteNo").asText());
        assertThat(fav).as("收藏夹里必须有刚收藏的那家 —— 从 pageSites(1,200) 里挑的话它不会出现").contains(last);
    }

    /**
     * 借出确认页按机柜反查站点 —— 四处里后果最重的那一处。
     *
     * <p>站点落在第一页之外时反查返 {@code null}，于是 {@code siteName} 退回机柜自带的
     * {@code locationName}、报价退回兜底值。<b>钉住 siteName</b>：它是「站点真的查到了」
     * 这件事在出参里唯一看得见的证据，而错价是看不见的（兜底价也是个合理的数字）。
     */
    @Test
    @DisplayName("借出确认页按机柜反查站点：站点在第一页之外也要查到，否则报价退回兜底值")
    void availabilityResolvesSiteBeyondFirstPage() {
        String venue = jdbc.queryForObject(
                "SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        String site = "STPG" + rnd();
        String siteName = "确认页反查测试站点 " + site;
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours, region_id)"
                + " VALUES (?, 'MAIN', ?, ?, 'ACTIVE', '00:00-23:59', 'R-TEST')", site, venue, siteName);
        // 后插的站点按 id 升序排在最后 —— 只要总数过 200 就在第一页之外
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM loc_site WHERE archived_at IS NULL", Integer.class))
                .as("本例要求站点总数超过 200").isGreaterThan(200);

        String cab = "CBPG" + rnd();
        cabinets.add(cab);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, site_no, slot_total,"
                        + " available_count, status, online_status, last_heartbeat_at)"
                        + " VALUES (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, 8, 3, 'DEPLOYED', 'ONLINE', NOW(3))",
                cab, cab, site);

        String phone = "+9715" + (10000000 + new java.util.Random().nextInt(89999999));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        String token = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();

        JsonNode a = get("/mp/nearby/cabinets/" + cab + "/availability", token).okData();
        assertThat(a.path("siteName").asText())
                .as("站点没查到时这里会退回机柜的 locationName，同时报价悄悄变成兜底值")
                .isEqualTo(siteName);
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
