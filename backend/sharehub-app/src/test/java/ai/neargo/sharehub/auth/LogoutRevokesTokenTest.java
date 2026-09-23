package ai.neargo.sharehub.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登出端到端吊销（W1 依赖的后端契约）。
 *
 * <p><b>为什么补这条</b>：{@code MemoryTokenStoreTest} 已经验了 store 级的 {@code revoke()}，
 * 但**没有任何测试证明 {@code POST /api/auth/logout} 真的让令牌对后续请求失效**。
 * 2026-09-23 之前这个缺口没人踩到，原因很朴素 —— <b>两个前端都没调过这个端点</b>，
 * 只清本地存储就算登出了（ops-web `lib/auth.ts` / c-app `stores/user.ts`）。
 *
 * <p>现在两端都改成先请后端吊销再清本地，这条契约成了它们的依赖：
 * 如果哪天 logout 悄悄退化成"返回 ok 但不吊销"，前端不会有任何症状，
 * 用户点了退出、令牌照常可用 —— 正是修复前的那个状态。所以它值一条端到端测试。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sharehub.dev-mode.enabled=false",
                "sharehub.admin.password=s3cret-for-test",
                "sharehub.admin.role=ADMIN",
        })
class LogoutRevokesTokenTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("登出后原令牌立即失效（前端只清本地 = 没登出）")
    void logoutMakesTokenUnusable() throws Exception {
        String token = login();

        // 前置：这个令牌现在是能用的 —— 不先证明它有效，下面的「失效」可能只是它从来就没生效过
        assertThat(authenticated(token))
                .as("刚登录的令牌必须可用，否则本测试的后半段没有意义")
                .isTrue();

        JsonNode out = logout(token);
        assertThat(out.path("data").path("ok").asBoolean(false) || out.path("code").asInt(-1) == 0)
                .as("登出本身要成功").isTrue();

        assertThat(authenticated(token))
                .as("登出后令牌必须立刻不认 —— 否则共用电脑上点完退出走人，"
                        + "下一个人拿 localStorage 里的旧令牌仍能调接口")
                .isFalse();
    }

    @Test
    @DisplayName("无令牌调登出不炸（前端在会话已失效时也可能调到）")
    void logoutWithoutTokenIsHarmless() throws Exception {
        JsonNode out = logout(null);
        assertThat(out.path("code").asInt(-1))
                .as("没有令牌可吊销不是错误，静默成功即可")
                .isZero();
    }

    @Test
    @DisplayName("吊销只影响这一个令牌，不牵连同账号的其它会话")
    void revokeIsPerToken() throws Exception {
        String a = login();
        String b = login();
        assertThat(a).isNotEqualTo(b);

        logout(a);

        assertThat(authenticated(a)).as("被登出的那个失效").isFalse();
        assertThat(authenticated(b))
                .as("另一台设备的会话不该被连坐 —— 否则手机上退个登录，电脑上也被踢")
                .isTrue();
    }

    // ───────────────────────── helpers ─────────────────────────

    private String login() throws Exception {
        return post("/api/auth/login", Map.of(
                "username", "admin", "password", "s3cret-for-test", "role", "ADMIN"))
                .path("data").path("token").asText();
    }

    /** 拿令牌问 /api/auth/me：认了才算这个会话还活着。 */
    private boolean authenticated(String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me"))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        JsonNode body = json.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
        return body.path("data").path("authenticated").asBoolean(false);
    }

    private JsonNode logout(String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/logout"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return json.readTree(http.send(b.build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    private JsonNode post(String path, Map<String, ?> body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        return json.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
    }
}
