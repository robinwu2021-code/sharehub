package ai.neargo.sharehub.auth;

import ai.neargo.sharehub.portal.platform.OpsKitIntrospectController;
import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpsKit 令牌内省（21/06 §八）：运营台登录一次，流程与工单平台就认得这个人。
 * 约定三条都要钉住 —— 无效令牌一律 200 active:false（不区分原因）、调用方凭据不对才 401、与认证过滤器同一条校验。
 */
@DisplayName("OpsKit 令牌内省")
class OpsKitIntrospectTest extends ApiTestSupport {

    private static final String BASIC = "Basic " + Base64.getEncoder()
            .encodeToString("opskit-test:introspect-test-secret".getBytes(StandardCharsets.UTF_8));

    @Autowired StaffTokenAuthFilter filter;

    private record R(int status, JsonNode body) { }

    private R introspect(String token, String authorization) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/opskit/introspect"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(token == null ? "" : "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)));
        if (authorization != null) b.header("Authorization", authorization);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new R(r.statusCode(), json.readTree(r.body()));
    }

    @Test
    @DisplayName("员工令牌：active、编号、权限、租户；应答是 RFC 7662 裸 JSON，不带手机号")
    void staff() throws Exception {
        R r = introspect(login("OPS"), BASIC);
        assertThat(r.status).isEqualTo(200);
        assertThat(r.body.path("active").asBoolean()).isTrue();
        assertThat(r.body.path("sub").asText()).isNotBlank();
        assertThat(r.body.path("realm").asText()).isEqualTo("STAFF");
        assertThat(r.body.path("tenant").asText()).isNotBlank();
        assertThat(r.body.path("perms").isArray() && r.body.path("perms").size() > 0).as("OPS 有权限码").isTrue();
        // 不包 Result{code,message,data}：OpsKit 按 RFC 7662 读顶层 active
        assertThat(r.body.has("code")).isFalse();
        // 全量范围的员工发 "*"（不发 = 没有范围，打了标签的单就看不到了）
        assertThat(r.body.path("scopes").toString()).isEqualTo("[\"*\"]");
        assertThat(r.body.toString()).doesNotContain("phone").doesNotContain("mobile");
    }

    @Test
    @DisplayName("代理令牌：范围标签 agent:{编号}（OpsKit 据此只给他看自己片区的单）")
    void agentScopes() throws Exception {
        R r = introspect(loginAgent("AG002"), BASIC);
        assertThat(r.body.path("active").asBoolean()).isTrue();
        assertThat(r.body.path("realm").asText()).isEqualTo("AGENT");
        assertThat(r.body.path("scopes").toString()).contains("agent:AG002");
    }

    @Test
    @DisplayName("无效令牌一律 200 active:false —— 伪造、C 端、空；原因不区分")
    void inactive() throws Exception {
        for (String t : new String[] {"stk_" + "0".repeat(32), "not-a-token", ""}) {
            R r = introspect(t, BASIC);
            assertThat(r.status).as(t).isEqualTo(200);
            assertThat(r.body).as(t).isEqualTo(json.readTree("{\"active\":false}"));
        }
        R c = introspect(consumerToken(), BASIC);
        assertThat(c.body.path("active").asBoolean()).as("C 端令牌不是运营身份").isFalse();
    }

    @Test
    @DisplayName("登出后立刻失效（与认证过滤器同一条校验）")
    void revoked() throws Exception {
        String t = login("CS");
        assertThat(introspect(t, BASIC).body.path("active").asBoolean()).isTrue();
        post("/api/auth/logout", Map.of(), t);
        assertThat(introspect(t, BASIC).body.path("active").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("调用方凭据缺失或不符 → 401；令牌再有效也不给答案")
    void callerMustAuthenticate() throws Exception {
        String t = login("OPS");
        assertThat(introspect(t, null).status).isEqualTo(401);
        String wrong = "Basic " + Base64.getEncoder().encodeToString("opskit-test:guess".getBytes(StandardCharsets.UTF_8));
        R r = introspect(t, wrong);
        assertThat(r.status).isEqualTo(401);
        assertThat(r.body.has("active")).isFalse();
    }

    @Test
    @DisplayName("凭据没配 → 一律 401（fail-closed，不退化成放行）")
    void failClosedWhenUnconfigured() throws Exception {
        OpsKitIntrospectController c = new OpsKitIntrospectController(filter, "", "");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/opskit/introspect");
        req.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(":".getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        c.introspect(login("OPS"), req, resp);
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).doesNotContain("active");
    }

    private String consumerToken() {
        String phone = "+9715" + (10000000 + new java.util.Random().nextInt(89999999));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        return post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
    }
}
