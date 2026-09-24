package ai.neargo.sharehub.support;

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
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 免密 + 多角色登录只在 dev-mode 下成立（TDD-auth-security-hotfix）：
        // 生产是 fail-closed（未配 sharehub.admin.password 即拒登）且角色只由账号决定。
        // 真实账号登录的回归要等 A3 的凭据库；在那之前集成测试显式开 dev-mode。
        // **生产姿态**（dev-mode 关）由 SecurityHotfixTest 专门守。
        properties = "sharehub.dev-mode.enabled=true")
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

    /**
     * 带一个自定义请求头的 POST。用途只有一个：**验证服务端不采信某个头**。
     * 正常业务不该需要它 —— 需要的话说明有个本该由服务端决定的东西交给了客户端。
     */
    protected Resp postWithHeaders(String path, Object body, String token, String... headerPairs) {
        if (headerPairs.length % 2 != 0) {
            throw new IllegalArgumentException("请求头要成对给（名, 值）：" + headerPairs.length + " 个");
        }
        HttpRequest.Builder b = reqBuilder(path, token).header("Content-Type", "application/json");
        for (int i = 0; i < headerPairs.length; i += 2) {
            b.header(headerPairs[i], headerPairs[i + 1]);
        }
        return send(b.POST(HttpRequest.BodyPublishers.ofString(toJson(body))).build());
    }

    /** PUT（全站仅「整体覆盖」语义用它：角色权限、数据范围）。 */
    protected Resp put(String path, Object body, String token) {
        HttpRequest.Builder b = reqBuilder(path, token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(toJson(body)));
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
