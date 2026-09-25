package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 领券中心要**够得着**（C-CP-01）。
 *
 * <h2>这条用例挡的是一个「有实现但没有入口」的功能</h2>
 * {@code POST /mp/user/coupons/{couponNo}/claim} 早就实现了，它要的是**券模板号**。
 * 而 C 端此前唯一的券列表 {@code GET /mp/user/coupons} 返的是用户**已领到手的券实例**，
 * 从里面取不到任何可领的模板号 —— 于是领券这件事在界面上根本无从发起。
 * 文档把「领券中心」和「我的券包」指到了同一个端点，但那个端点只能回答后者。
 * 这种缺口不报错：页面能打开、列表能加载，只是永远没有券可领。
 *
 * <h2>为什么要专门断言「两个键不能混用」</h2>
 * 券包里那行的键叫 {@code couponNo}（{@code CP…}，券实例），领券要传的是 {@code tplNo}（模板）。
 * 两个列表摆在同一个页面上，键名一旦一样就一定有人传错，而传错的表现是
 * 400「券模板不存在」—— 看着像脏数据，其实是传了另一张表的主键。
 */
class ConsumerCouponClaimTest extends ApiTestSupport {

    private static String freshPhone() {
        return "+9715008" + (100_000 + ThreadLocalRandom.current().nextInt(800_000));
    }

    private String consumerToken(String phone) {
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        return post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
    }

    /** 建一个本次专用的模板：共享测试库是累积的，复用别人的模板会让「已领过」这类断言偶发红。 */
    private String freshTpl(String admin, String status, Integer stock) {
        String tplNo = "CTPL" + ThreadLocalRandom.current().nextInt(100_000, 999_999);
        post("/api/user/coupons", Map.of(
                "tplNo", tplNo, "name", "领券中心用例 " + tplNo, "type", "CUT",
                "value", new BigDecimal("5.00"), "threshold", new BigDecimal("20.00"),
                "currency", "AED", "stock", stock, "status", status), admin).okData();
        return tplNo;
    }

    private JsonNode rowOf(JsonNode claimable, String tplNo) {
        for (JsonNode n : claimable) {
            if (tplNo.equals(n.path("tplNo").asText())) return n;
        }
        return null;
    }

    @Test
    @DisplayName("★★ 领券闭环：列得出 → 领得到 → 券包里有 → 那一行变已领")
    void claim_center_closes_the_loop() {
        String admin = login("ADMIN");
        String tplNo = freshTpl(admin, "ACTIVE", 0); // stock=0 = 不限量
        String token = consumerToken(freshPhone());

        JsonNode before = rowOf(get("/mp/user/coupons/claimable", token).okData(), tplNo);
        assertThat(before).as("刚建的 ACTIVE 模板必须出现在领券中心，否则 claim 无从发起").isNotNull();
        assertThat(before.path("claimed").asBoolean()).isFalse();
        assertThat(before.path("remaining").isNull())
                .as("stock=0 的语义是不限量，不是剩 0 张 —— 回 0 会让按钮置灰").isTrue();

        JsonNode got = post("/mp/user/coupons/" + tplNo + "/claim", Map.of(), token).okData();
        String couponNo = got.path("couponNo").asText();
        assertThat(couponNo).as("领到的是券实例号").startsWith(BizKey.COUPON);
        assertThat(got.path("tplNo").asText()).isEqualTo(tplNo);

        assertThat(findInPages("/mp/user/coupons", "couponNo", couponNo, token))
                .as("领完券包里要看得到 %s", couponNo).isNotNull();

        JsonNode after = rowOf(get("/mp/user/coupons/claimable", token).okData(), tplNo);
        assertThat(after).as("已领过的模板仍要留在列表里 —— 消失会让人以为券丢了").isNotNull();
        assertThat(after.path("claimed").asBoolean())
                .as("已领过却回 claimed=false，按钮照样可点，用户点了才知道「已领过」").isTrue();
    }

    @Test
    @DisplayName("★ 券包的 couponNo 不能当模板号用 —— 传错的表现是 400，不是空列表")
    void instance_key_is_not_a_template_key() {
        String admin = login("ADMIN");
        String tplNo = freshTpl(admin, "ACTIVE", 0);
        String token = consumerToken(freshPhone());

        String couponNo = post("/mp/user/coupons/" + tplNo + "/claim", Map.of(), token)
                .okData().path("couponNo").asText();

        assertThat(post("/mp/user/coupons/" + couponNo + "/claim", Map.of(), token).status)
                .as("拿券实例号去领券应当被拒 —— 两个列表的键不同源，不能互换")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("重复领券是幂等的：拿回同一张，不发第二张")
    void claiming_twice_returns_the_same_coupon() {
        String admin = login("ADMIN");
        String tplNo = freshTpl(admin, "ACTIVE", 0);
        String token = consumerToken(freshPhone());

        String first = post("/mp/user/coupons/" + tplNo + "/claim", Map.of(), token).okData().path("couponNo").asText();
        String second = post("/mp/user/coupons/" + tplNo + "/claim", Map.of(), token).okData().path("couponNo").asText();
        assertThat(second).as("幂等：重复点「领取」不该发第二张").isEqualTo(first);
    }

    @Test
    @DisplayName("停用的模板不进领券中心 —— 否则点了才报「券模板已停用」")
    void paused_templates_are_not_listed() {
        String admin = login("ADMIN");
        String tplNo = freshTpl(admin, "PAUSED", 0);
        String token = consumerToken(freshPhone());

        assertThat(rowOf(get("/mp/user/coupons/claimable", token).okData(), tplNo))
                .as("PAUSED 模板不该出现在可领列表里").isNull();
    }
}
