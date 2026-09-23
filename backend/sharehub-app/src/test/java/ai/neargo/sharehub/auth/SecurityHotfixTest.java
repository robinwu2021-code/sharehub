package ai.neargo.sharehub.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产姿态守门测试（TDD-auth-security-hotfix / v4/06 §〇 A0）。
 *
 * <p>与 {@code ApiTestSupport} 的其余集成测试相反：那些显式开 dev-mode 以便多角色登录，
 * **本类刻意不开**，验证「什么都不配」时系统是关着的 —— 这正是 2026-09-23 之前线上出事的配置。
 *
 * <p>守三条：固定码 {@code 000000} 不通、验证码不回传、未配口令不许登录。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sharehub.dev-mode.enabled=false",
                "sharehub.admin.password=",     // 显式留空：模拟「漏配口令」
        })
class SecurityHotfixTest {

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext ctx;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("dev-mode 关：固定码 000000 不能登录 C 端")
    void masterCodeRejectedWhenDevModeOff() throws Exception {
        JsonNode body = post("/mp/auth/login", Map.of(
                "grantType", "phone_otp", "phone", "+971500000001", "otp", "000000"));
        // 业务码非 0 即登录失败（具体码由 GlobalExceptionHandler 映射，这里只断言「没发出 token」）
        assertThat(body.path("data").path("token").asText("")).isEmpty();
        assertThat(body.path("code").asInt(-1)).isNotZero();
    }

    @Test
    @DisplayName("dev-mode 关：发码接口不回传验证码")
    void otpNotEchoedWhenDevModeOff() throws Exception {
        JsonNode body = post("/mp/auth/otp", Map.of("phone", "+971500000002"));
        JsonNode data = body.path("data");
        assertThat(data.path("sent").asBoolean()).isTrue();
        assertThat(data.has("devCode")).as("验证码绝不能回传").isFalse();
    }

    @Test
    @DisplayName("未配 admin 口令 + dev-mode 关：任意账号登录运营端都失败")
    void staffLoginFailsClosedWhenPasswordNotConfigured() throws Exception {
        JsonNode body = post("/api/auth/login", Map.of(
                "username", "anyone", "password", "whatever", "role", "ADMIN"));
        assertThat(body.path("data").path("token").asText("")).isEmpty();
        assertThat(body.path("code").asInt(-1)).isNotZero();
    }

    @Test
    @DisplayName("默认配置不装配演示种子（seed.enabled 默认 false）")
    void seedersNotWiredByDefault() {
        // 本类没有设 sharehub.seed.enabled，走 application.yml 的默认值
        assertThat(ctx.getBeanNamesForType(ai.neargo.sharehub.seed.domain.VendorSeeder.class))
                .as("空库启动不该灌演示数据")
                .isEmpty();
    }

    private JsonNode post(String path, Map<String, ?> body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return json.readTree(resp.body());
    }
}
