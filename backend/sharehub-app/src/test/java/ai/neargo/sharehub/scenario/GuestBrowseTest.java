package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 游客（没登录）能看附近网点、门店卡与公告；个人数据仍然要登录。
 *
 * <h3>为什么要钉这一条</h3>
 * 需求（C 端功能清单 §一）写的是「游客：可浏览附近网点/价格；扫码借出前强制登录」，
 * SecurityConfig 也放行了 /mp/nearby/** —— 而控制器里另有一句 ConsumerContext.require()，
 * 把放进来的游客又拒了回去。两处各自读起来都对，合起来是：**游客打开 App，首页 403，
 * 会话守卫把他直接送去登录页**。2026-09-27 在 Android 模拟器上装机实测撞到。
 *
 * <p>所以钉的是**两个方向**：该放的放了（不只是安全链放行，而是真拿到数据），
 * 不该放的仍然拦着（放行一口时最容易顺手放多）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GuestBrowseTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("游客：找柜 / 门店卡 / 公告都能看；进行中订单、资料、借出仍要登录")
    void guestCanBrowseButNotReadPersonalData() {
        JsonNode list = get("/mp/nearby/cabinets", null).okData();
        assertThat(list.size()).as("游客要能看到附近网点 —— 这是 App 首页的主数据").isPositive();

        String cabinetNo = list.get(0).path("cabinetNo").asText();
        String siteNo = list.get(0).path("siteNo").asText();
        assertThat(list.get(0).path("pricePerHour").isMissingNode())
                .as("价格游客可看（需求原文：可浏览附近网点/价格）—— 在列表上就有").isFalse();

        JsonNode site = get("/mp/sites/" + siteNo, null).okData();
        assertThat(site.path("favorite").asBoolean(true))
                .as("门店卡游客可看；「已收藏」对游客恒为 false（没有属主可查）").isFalse();

        assertThat(get("/mp/notice", null).status).as("公告是广播，没有个人数据").isEqualTo(200);

        // —— 反方向：放行一个口时最容易顺手放多 ——
        assertThat(get("/mp/trade/orders/ongoing", null).status)
                .as("进行中订单是个人数据，游客必须 401").isEqualTo(401);
        assertThat(get("/mp/user/profile", null).status)
                .as("资料是个人数据，游客必须 401").isEqualTo(401);
        assertThat(post("/mp/trade/orders/rent", Map.of("cabinetNo", cabinetNo), null).status)
                .as("借出前强制登录（需求原文）").isEqualTo(401);
    }

    @Test
    @DisplayName("门店卡的「可借」= 列表里这家店可借柜的宝数之和（同一个事实不许两个来源）")
    void storeCardBorrowCountAgreesWithList() {
        JsonNode list = get("/mp/nearby/cabinets", null).okData();
        java.util.Map<String, Integer> expected = new java.util.HashMap<>();
        for (JsonNode c : list) {
            expected.merge(c.path("siteNo").asText(),
                    "ACTIVE".equals(c.path("status").asText()) ? c.path("availableBorrow").asInt() : 0,
                    Integer::sum);
        }
        assertThat(expected).as("前提：列表里得有站点").isNotEmpty();
        for (var e : expected.entrySet()) {
            int onCard = get("/mp/sites/" + e.getKey(), null).okData().path("availableBorrow").asInt(-1);
            assertThat(onCard)
                    .as("""
                            站点 %s：门店卡「可借」与列表不一致。
                            此前门店卡把离线 / 故障 / 停用柜里的宝也算进去 —— 卡上写「可借 29」，
                            列表里这家店每台柜都是「不可用」，用户点「借」进的是一台借不了的柜。""", e.getKey())
                    .isEqualTo(e.getValue());
        }
    }

    @Test
    @DisplayName("登录后门店卡照常带「已收藏」（放行游客没把登录态的信息弄丢）")
    void loggedInStillGetsFavoriteFlag() {
        String phone = "+97150" + (System.nanoTime() % 10000000L);
        post("/mp/auth/otp", Map.of("phone", phone), null).okData();
        String token = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", phone, "otp", "000000"), null)
                .okData().path("token").asText();
        String siteNo = get("/mp/nearby/cabinets", token).okData().get(0).path("siteNo").asText();

        post("/mp/user/favorites/" + siteNo, Map.of(), token).okData();
        assertThat(get("/mp/sites/" + siteNo, token).okData().path("favorite").asBoolean(false))
                .as("收藏过的店，登录态看门店卡要显示已收藏").isTrue();
    }
}
