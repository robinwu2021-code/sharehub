package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务拒绝要说人话（2026-09-25）。
 *
 * <h2>此前发生了什么</h2>
 * 「券已领完」「押金已解冻，不能再买断」「冷静期内已有注销申请」这类**业务拒绝**
 * 抛的是裸 {@code IllegalStateException}，而 {@code GlobalExceptionHandler} 没有它的映射 ⇒
 * 全部落兜底：<b>HTTP 500「服务器错误」</b>，外加一行 {@code ERROR 未处理异常} 带堆栈。
 * 于是调用方分不清「券领完了」和「数据库挂了」，而 ERROR 级别也因此失去意义 ——
 * 按 CLAUDE.md 的口径它表示「需要人介入且不介入会持续出错」，券领完不需要任何人介入。
 *
 * <h2>为什么断言 message 而不只是状态码</h2>
 * 只断言「不是 200」的话，把消息换成一句笼统的「操作失败」照样绿 ——
 * 而**让调用方看到真原因**正是这次要修的东西。所以逐条钉住原话。
 *
 * <h2>为什么选这三个</h2>
 * 一个 C 端（券）、一个运营端（押金）、一个跨冷静期的时序类（注销），
 * 三条分别经过 {@code UserCouponServiceImpl} / {@code DepositServiceImpl} /
 * {@code UserLogoffServiceImpl}，覆盖三个不同的域，而不是同一处换三个入参。
 */
class BizRejectionStatusTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private static String freshPhone() {
        return "+9715005" + (100_000 + ThreadLocalRandom.current().nextInt(800_000));
    }

    private String consumerToken(String phone) {
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        return post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
    }

    @Test
    @DisplayName("★★ 券领完 → 409 且带原话，不是 500「服务器错误」")
    void sold_out_coupon_is_409_with_the_real_reason() {
        String admin = login("ADMIN");
        // stock=1 且已发放 1 张 → 下一个人必然领不到
        String tplNo = "CTPL" + ThreadLocalRandom.current().nextInt(100_000, 999_999);
        post("/api/user/coupons", Map.of(
                "tplNo", tplNo, "name", "售罄用例 " + tplNo, "type", "CUT",
                "value", new BigDecimal("5.00"), "threshold", BigDecimal.ZERO,
                "currency", "AED", "stock", 1, "status", "ACTIVE"), admin).okData();

        post("/mp/user/coupons/" + tplNo + "/claim", Map.of(), consumerToken(freshPhone())).okData();

        Resp r = post("/mp/user/coupons/" + tplNo + "/claim", Map.of(), consumerToken(freshPhone()));
        assertThat(r.status).as("业务拒绝不该是 500").isEqualTo(409);
        assertThat(r.body.path("message").asText())
                .as("要把真原因给到调用方，不是一句「服务器错误」")
                .contains("券已领完");
    }

    /**
     * 押金：库里**一条 ord_deposit 都没有**（实测 0 行），而且没有任何端点能建它
     * （C 端的免押走 {@code pay_auth}，不是这张表）。所以前置自己种一行。
     *
     * <p><b>不写成「查不到就 return」</b> —— 那种守卫在共享库里会永远命中，
     * 于是这条用例天天绿、什么都没在测。本仓库已经吃过一次这种假绿的亏。
     */
    @Test
    @DisplayName("★★ 押金已解冻还要买断 → 409 且带原话")
    void buyout_after_release_is_409_with_the_real_reason() {
        String admin = login("ADMIN");
        String depositNo = "DEP" + ThreadLocalRandom.current().nextInt(100_000, 999_999);
        int seeded = jdbc.update(
                "INSERT INTO ord_deposit (deposit_no, tenant_id, order_no, c_user_no, amount, currency, status) "
                        + "VALUES (?, 'MAIN', ?, ?, 50.00, 'AED', 'RELEASED')",
                depositNo, "ORD-BIZREJ-" + depositNo, "CU-BIZREJ");
        assertThat(seeded).as("前置：应当种出一条 RELEASED 押金").isEqualTo(1);

        try {
            Resp r = post("/api/trade/deposits/" + depositNo + "/buyout", Map.of(), admin);
            assertThat(r.status).as("已解冻还买断是业务拒绝，不是服务器故障").isEqualTo(409);
            assertThat(r.body.path("message").asText()).contains("押金已解冻");
        } finally {
            jdbc.update("DELETE FROM ord_deposit WHERE deposit_no = ?", depositNo);
        }
    }

    @Test
    @DisplayName("★★ 冷静期内重复申请注销 → 409 且带冷静期截止时间")
    void duplicate_logoff_request_is_409_with_the_cooling_date() {
        String token = consumerToken(freshPhone());
        post("/mp/user/logoff", Map.of(), token).okData();

        Resp r = post("/mp/user/logoff", Map.of(), token);
        assertThat(r.status).as("已有进行中的申请，是业务拒绝不是服务器故障").isEqualTo(409);
        assertThat(r.body.path("message").asText())
                .as("要带上冷静期截止时间，否则用户不知道等到哪天")
                .contains("已有进行中的注销申请");
    }

    @Test
    @DisplayName("基础设施故障仍是 500——这次改动刻意没碰它们")
    void infrastructure_failures_are_still_500() {
        // 不存在的套餐走 IllegalArgumentException → 400（业务入参错），
        // 与本次改的 409（状态冲突）是两回事，顺带钉住别被一起改掉
        String token = consumerToken(freshPhone());
        assertThat(post("/mp/user/recharge", Map.of("packageNo", "RP-NOPE-0000"), token).status)
                .as("入参错仍是 400，不该被顺手改成 409").isEqualTo(400);
    }
}
