package ai.neargo.powerbank.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成测试基类：真实端口起服 + JDK {@link HttpClient} 走全链路（含 {@code ApiResponseWrapper} 包裹）。
 *
 * <p>不用 Boot 的 TestRestTemplate/MockMvc —— Spring Boot 4.0 已把这些 web 测试助手拆出独立模块，
 * 本机私仓未含（见 SmokeTest 注释）。子类继承后即可 {@code login(...)/get(...)/post(...)}。
 *
 * <p>约定：登录换 Bearer token；断言基于 powerbank 契约 {@code {code,msg,data}}，
 * 列表 {@code data.records/total/page/size}。测试数据见 {@code resources/fixtures/operator-daily.json}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class ApiTestSupport {

    @LocalServerPort
    protected int port;

    protected final HttpClient http = HttpClient.newHttpClient();
    protected final ObjectMapper json = new ObjectMapper();

    /** 一次响应：HTTP 状态 + 解析后的包体（{@code {code,msg,data}}）。 */
    public final class Resp {
        public final int status;
        public final JsonNode body;

        Resp(int status, JsonNode body) {
            this.status = status;
            this.body = body;
        }

        public int code() { return body.path("code").asInt(-999); }
        public String msg() { return body.path("message").asText(""); }   // neargo Result: message
        public JsonNode data() { return body.path("data"); }

        /** 断言业务成功（HTTP 200 + code:0 + message:"success"），返回 data 便于链式断言。 */
        public JsonNode okData() {
            assertThat(status).as("HTTP 状态").isEqualTo(200);
            assertThat(code()).as("业务码 (message=%s)", msg()).isEqualTo(0);
            assertThat(msg()).isEqualTo("success");
            return data();
        }
    }

    // —— 认证 ——

    protected String login(String role) {
        return login(role, role.toLowerCase() + ".user", null);
    }

    protected String loginAgent(String agentNo) {
        return login("AGENT", "agent." + agentNo, agentNo);
    }

    protected String login(String role, String username, String agentNo) {
        Map<String, Object> b = new HashMap<>();
        b.put("username", username);
        b.put("role", role);
        if (agentNo != null) b.put("agentNo", agentNo);
        Resp r = post("/api/auth/login", b, null);
        assertThat(r.status).as("登录 HTTP 状态").isEqualTo(200);
        return r.data().path("token").asText();
    }

    // —— 请求 ——

    protected Resp get(String path, String token) {
        return send(reqBuilder(path, token).GET().build());
    }

    protected Resp post(String path, Object body, String token) {
        String payload = toJson(body);
        HttpRequest.Builder b = reqBuilder(path, token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        return send(b.build());
    }

    private HttpRequest.Builder reqBuilder(String path, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return b;
    }

    private Resp send(HttpRequest req) {
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            String bodyStr = resp.body() == null || resp.body().isBlank() ? "{}" : resp.body();
            return new Resp(resp.statusCode(), json.readTree(bodyStr));
        } catch (Exception e) {
            throw new IllegalStateException("HTTP 请求失败: " + req.uri(), e);
        }
    }

    private String toJson(Object body) {
        if (body == null) return "";
        if (body instanceof JsonNode || body instanceof String) return body.toString();
        try {
            return json.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("序列化请求体失败", e);
        }
    }

    /** 载入 {@code resources/fixtures/operator-daily.json} 测试数据。 */
    protected JsonNode fixtures() {
        try (var in = getClass().getResourceAsStream("/fixtures/operator-daily.json")) {
            assertThat(in).as("fixtures/operator-daily.json 存在").isNotNull();
            return json.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException("载入测试数据失败", e);
        }
    }
}
