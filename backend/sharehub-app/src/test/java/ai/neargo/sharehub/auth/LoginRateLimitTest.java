package ai.neargo.sharehub.auth;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录防刷（需求方案 §四）：同一 IP 每分钟登录次数有上限；商家发码被限流时与「号不存在」回得一样。
 *
 * <p>测试 profile 把登录上限调得很高（整套用例都从 127.0.0.1 登录），本类单独把它压低来验。
 * 用的 IP 由 X-Forwarded-For 指定，与别的用例互不干扰。
 */
@TestPropertySource(properties = {
        "sharehub.security.login-rate-per-minute=3",
        "sharehub.auth.otp.daily-limit=1",
})
class LoginRateLimitTest extends ApiTestSupport {

    @Test
    @DisplayName("★★ 同一 IP 每分钟超过上限 → 429；限的是匿名登录，不按账号存不存在区分")
    void login_is_rate_limited_per_ip() {
        String ip = "10.9." + (System.nanoTime() % 200) + ".7";
        Map<String, Object> body = Map.of("realm", "AGENT", "identifier", "nobody" + System.nanoTime() + "@example.com",
                "password", "wrong");
        for (int i = 0; i < 3; i++) assertThat(postWithHeaders("/api/auth/login", body, null, "X-Forwarded-For", ip).status).isEqualTo(401);
        assertThat(postWithHeaders("/api/auth/login", body, null, "X-Forwarded-For", ip).status).isEqualTo(429);
    }

    @Test
    @DisplayName("★ 商家发码到了当日上限：仍回 ok，与号不存在一样（不泄露账号存在）")
    void throttled_merchant_otp_looks_like_unknown() {
        String mail = "rl" + System.nanoTime() + "@example.com";
        Map<String, Object> b = new java.util.HashMap<>();
        b.put("email", mail);
        b.put("operatorName", "限流测试 " + mail);
        b.put("operatorType", "AGENT");
        String applyNo = post("/api/agent/applies", b, login("BD")).okData().path("applyNo").asText();
        String admin = login("ADMIN");
        post("/api/agent/applies/" + applyNo + "/accept", Map.of(), admin).okData();
        post("/api/agent/applies/" + applyNo + "/audit", Map.of("approve", true), admin).okData();

        assertThat(post("/api/auth/otp", Map.of("identifier", mail), null).status).isEqualTo(200);
        Resp second = post("/api/auth/otp", Map.of("identifier", mail), null);
        Resp unknown = post("/api/auth/otp", Map.of("identifier", "x" + mail), null);
        assertThat(second.status).isEqualTo(200);
        assertThat(second.body.path("data").has("code")).as("被限流时不发码、不回显").isFalse();
        assertThat(second.body.path("data")).isEqualTo(unknown.body.path("data"));
    }
}
