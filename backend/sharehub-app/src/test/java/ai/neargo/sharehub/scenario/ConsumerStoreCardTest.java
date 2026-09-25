package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 门店卡片要带得动一张卡片（C-MAP / C-ME 收藏）。
 *
 * <h2>两个都不报错的缺陷</h2>
 * ① <b>坐标一直返 0</b>。`loc_site` 的 lat/lng 早就有列也有数据，而找柜 BFF 里
 * 三个字段被硬编码成 0，注释写着「DDL 缺口，待 V31」—— 列补上之后没人回来删那条注释。
 * 后果是地图把每一家店都钉在 0,0（几内亚湾），列表里写着「0 m」，
 * 读起来是「你就站在店里」。没有任何卡口能发现，因为这个端点返的是
 * {@code Map<String,Object>}，对齐脚本按出参类型比字段，Map 的形状是空的。
 *
 * ② <b>收藏列表返的不是门店卡片</b>。后端给的是 {@code FavoriteItem}
 * （只有 siteNo/siteName/createdAt），而收藏页渲染地址、可借可还、价格、距离 ——
 * 全是 undefined，行键 {@code cabinetNo} 还会全撞在 undefined 上。
 *
 * <h2>为什么断言「算不出要回 null 而不是 0」</h2>
 * 0 是一个合法距离。把「没法算」表示成 0，界面就没有任何办法区分
 * 「这家店就在你旁边」和「我不知道它在哪」—— 而这两句话对用户是相反的意思。
 */
class ConsumerStoreCardTest extends ApiTestSupport {

    /** 迪拜购物中心附近，用来验距离算得出且量级合理。 */
    private static final double DUBAI_LAT = 25.1972;
    private static final double DUBAI_LNG = 55.2796;

    @Autowired
    private JdbcTemplate jdbc;

    private String consumerToken() {
        String phone = "+9715006" + (100_000 + ThreadLocalRandom.current().nextInt(800_000));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        return post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
    }

    /** 库里任取一个有坐标的站点。共享测试库是累积的，写死站点号迟早失效。 */
    private String siteWithCoords() {
        return jdbc.queryForObject(
                "SELECT site_no FROM loc_site WHERE lat IS NOT NULL AND lng IS NOT NULL "
                        + "AND archived_at IS NULL ORDER BY id LIMIT 1", String.class);
    }

    @Test
    @DisplayName("★★ 找柜列表要出真坐标——返 0 的话地图把每家店都钉在几内亚湾")
    void nearby_returns_real_coordinates() {
        String token = consumerToken();
        JsonNode rows = get("/mp/nearby/cabinets", token).okData();
        assertThat(rows.isArray()).isTrue();

        long withCoords = 0;
        for (JsonNode r : rows) {
            // ⚠️ 没录坐标的站点回的是 **null**，而 Jackson 的 asDouble() 对 null 也给 0.0 ——
            // 第一版直接断言「asDouble() != 0」，库里一加进没坐标的站点就红，
            // 而那不是缺陷、恰恰是「算不出就回 null」的正确表现。先判 isNull 再判值。
            if (r.path("lat").isNull()) continue;
            withCoords++;
            assertThat(r.path("lat").asDouble())
                    .as("有坐标就不该出 0 —— 0,0 是几内亚湾，没录坐标要回 null 而不是 0").isNotEqualTo(0.0);
        }
        assertThat(withCoords).as("库里有带坐标的站点，列表就该带出来").isGreaterThan(0);
    }

    @Test
    @DisplayName("★★ 给了定位才算距离；没给要回 null 而不是 0（0 会被渲染成「0 m」）")
    void distance_is_null_when_it_cannot_be_computed() {
        String token = consumerToken();

        for (JsonNode r : get("/mp/nearby/cabinets", token).okData()) {
            assertThat(r.path("distanceM").isNull())
                    .as("没给定位时距离算不出，必须是 null；0 在界面上是「你就站在店里」")
                    .isTrue();
        }

        JsonNode near = get("/mp/nearby/cabinets?lat=" + DUBAI_LAT + "&lng=" + DUBAI_LNG, token).okData();
        boolean any = false;
        Integer prev = null;
        for (JsonNode r : near) {
            if (r.path("distanceM").isNull()) continue;   // 没坐标的站点，排在最后
            int d = r.path("distanceM").asInt();
            assertThat(d).as("距离是米，迪拜城内不该出现上千公里").isBetween(0, 500_000);
            if (prev != null) assertThat(d).as("给了定位就要按距离升序").isGreaterThanOrEqualTo(prev);
            prev = d;
            any = true;
        }
        assertThat(any).as("有坐标的站点应当算得出距离").isTrue();
    }

    @Test
    @DisplayName("★★ 收藏列表返的是门店卡片，不是只有店名——否则收藏页整列表只剩一行字")
    void favorites_return_a_renderable_store_card() {
        String token = consumerToken();
        String siteNo = siteWithCoords();

        assertThat(get("/mp/user/favorites", token).okData()).as("新用户还没有收藏").isEmpty();

        post("/mp/user/favorites/" + siteNo, Map.of(), token).okData();

        JsonNode rows = get("/mp/user/favorites?lat=" + DUBAI_LAT + "&lng=" + DUBAI_LNG, token).okData();
        assertThat(rows).as("收藏之后列表里应当有这家店").isNotEmpty();

        JsonNode r = rows.get(0);
        assertThat(r.path("siteNo").asText()).isEqualTo(siteNo);
        // 这四个正是页面在渲染、而旧出参一个都没有的字段
        assertThat(r.hasNonNull("cabinetNo"))
                .as("行键用的是 cabinetNo —— 缺了它每一行的 key 都是 undefined").isTrue();
        assertThat(r.hasNonNull("address")).as("地址").isTrue();
        assertThat(r.has("availableBorrow")).as("可借数").isTrue();
        assertThat(r.has("availableReturn")).as("可还数").isTrue();
        assertThat(r.hasNonNull("pricePerHour")).as("价格").isTrue();
        assertThat(r.hasNonNull("distanceM")).as("给了定位就该有距离").isTrue();

        // 取消收藏后要消失 —— 否则用户以为取消了，下次进来还在
        post("/mp/user/favorites/" + siteNo, Map.of(), token).okData();
        assertThat(get("/mp/user/favorites", token).okData()).isEmpty();
    }
}
