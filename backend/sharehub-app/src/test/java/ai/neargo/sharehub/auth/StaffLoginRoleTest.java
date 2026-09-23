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
 * 口令闸已配置时的登录姿态（TDD-auth-security-hotfix §4 场景 6–7）。
 *
 * <p>账号角色**故意配成 VIEWER**，而请求体里传 `role:"ADMIN"` —— 验证后端
 * **不采信前端传的角色**。2026-09-23 之前：闸门关闭时前端传什么角色就是什么角色，
 * ops-web 正是写死 `role:"ADMIN"`（v4/06 §〇 第 1 条）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sharehub.dev-mode.enabled=false",
                "sharehub.admin.password=s3cret-for-test",
                "sharehub.admin.role=VIEWER",
        })
class StaffLoginRoleTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("口令正确：角色取账号配置，前端传的 ADMIN 被忽略")
    void roleComesFromAccountNotRequest() throws Exception {
        JsonNode data = post("/api/auth/login", Map.of(
                "username", "admin", "password", "s3cret-for-test", "role", "ADMIN")).path("data");

        assertThat(data.path("token").asText()).startsWith("stk_");
        assertThat(data.path("role").asText())
                .as("角色必须由账号决定，不能被前端指定")
                .isEqualTo("VIEWER");
    }

    @Test
    @DisplayName("口令错误：拒绝")
    void wrongPasswordRejected() throws Exception {
        JsonNode body = post("/api/auth/login", Map.of(
                "username", "admin", "password", "wrong", "role", "ADMIN"));
        assertThat(body.path("data").path("token").asText("")).isEmpty();
        assertThat(body.path("code").asInt(-1)).isNotZero();
    }

    @Test
    @DisplayName("口令对但用户名不是 admin：拒绝")
    void wrongUsernameRejected() throws Exception {
        JsonNode body = post("/api/auth/login", Map.of(
                "username", "someone", "password", "s3cret-for-test", "role", "ADMIN"));
        assertThat(body.path("data").path("token").asText("")).isEmpty();
        assertThat(body.path("code").asInt(-1)).isNotZero();
    }

    private JsonNode post(String path, Map<String, ?> body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        return json.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
    }
}
