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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会员方案此前**建不出来**：{@code mbr_plan} 有表、有实体、有 mapper，
 * C 端有会员方案页读它，{@code MemberPlanRow} 这个出参 DTO 都声明好了并注明
 * 「运营端方案维护」—— 唯独全仓没有一个端点能建或改它，表里 0 行。
 * 于是 C 端会员页永远是空列表，看起来像「本平台暂无会员方案」。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberPlanLifecycleTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    final List<String> plans = new ArrayList<>();

    @AfterAll
    void cleanup() {
        for (String p : plans) jdbc.update("DELETE FROM mbr_plan WHERE plan_no=?", p);
    }

    @Test
    @DisplayName("建方案 → 上架 → C 端看得见，且权益是结构化的")
    void planBecomesVisibleToConsumersOnlyAfterEnable() {
        String staff = login("admin");
        JsonNode created = post("/api/user/member-plans", Map.of(
                "name", "会员方案测试月卡", "cardType", "MONTHLY", "price", 29.9,
                "currency", "AED", "periodDays", 30, "sortNo", 1,
                "rights", Map.of("rentDiscount", 0.9, "freeMinutes", 30, "depositFree", true)), staff).okData();
        String planNo = created.path("planNo").asText();
        plans.add(planNo);

        assertThat(planNo).as("取号前缀 MP —— 不能用 PP，那是计价方案").startsWith("MP");
        assertThat(created.path("status").asText())
                .as("新建默认下架：建好就开卖会让一个还没填完权益的方案直接对 C 端可见")
                .isEqualTo("DISABLED");
        assertThat(created.path("rights").path("rentDiscount").asDouble())
                .as("权益要作为结构化对象回来，不是一段 JSON 字符串").isEqualTo(0.9);

        String token = consumerToken();
        assertThat(planNoesVisibleTo(token)).as("下架的方案不该出现在 C 端").doesNotContain(planNo);

        post("/api/user/member-plans/" + planNo + "/enable", Map.of(), staff).okData();
        assertThat(planNoesVisibleTo(token)).as("上架后 C 端才看得见").contains(planNo);

        post("/api/user/member-plans/" + planNo + "/disable", Map.of(), staff).okData();
        assertThat(planNoesVisibleTo(token)).as("下架后又该消失").doesNotContain(planNo);
    }

    /**
     * 「九折」填成 {@code 9} 要被拒。
     *
     * <p>这是最容易犯的那一种：折扣率不是折扣额，也不是「几折」这个整数。
     * 不拦的话应收会乘成**九倍**，而金额照样算得出来、接口一路 200 ——
     * 只有用户会发现，而且发现时钱已经扣了。
     */
    @Test
    @DisplayName("折扣率填成「9」被拒，不是默默按九倍价算")
    void discountRateOutOfRangeIsRejected() {
        var resp = post("/api/user/member-plans", Map.of(
                "name", "折扣率越界测试", "cardType", "RIGHTS", "price", 1,
                "rights", Map.of("rentDiscount", 9)), login("admin"));

        assertThat(resp.code()).as("应为业务失败（响应：%s）", resp.msg()).isNotZero();
    }

    /**
     * 没配权益的方案不许上架。
     *
     * <p>上架后 C 端看到的是「有价格、没权益」的卡片，买下去什么也不会发生 ——
     * 那比不上架糟得多，而且不报错。
     */
    @Test
    @DisplayName("权益为空的方案拒绝上架")
    void planWithoutRightsCannotBeEnabled() {
        String staff = login("admin");
        Map<String, Object> body = new HashMap<>();
        body.put("name", "无权益方案测试");
        body.put("cardType", "RIGHTS");
        body.put("price", 1);
        String planNo = post("/api/user/member-plans", body, staff).okData().path("planNo").asText();
        plans.add(planNo);

        var resp = post("/api/user/member-plans/" + planNo + "/enable", Map.of(), staff);
        assertThat(resp.code()).as("应为业务失败（响应：%s）", resp.msg()).isNotZero();
    }

    // —— 夹具 ——

    private List<String> planNoesVisibleTo(String token) {
        List<String> out = new ArrayList<>();
        for (JsonNode p : get("/mp/user/membership", token).okData()) out.add(p.path("planNo").asText());
        return out;
    }

    private String consumerToken() {
        String phone = "+9715" + (10000000 + new java.util.Random().nextInt(89999999));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        return post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
    }
}
