package ai.neargo.sharehub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冒烟：真实端口起服 + 走全链路（含 {@code ApiResponseWrapper} 包裹），
 * 校验 powerbank 契约（{@code code:0/msg:"ok"} + 分页 {@code data.records/total}）。
 *
 * <p>用 JDK HttpClient + Jackson（web 起步已带）而非 boot 的 TestRestTemplate/MockMvc——
 * Spring Boot 4.0 已把这些 web 测试助手拆出独立模块，本机私仓未含，故不依赖它们。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 本类的两个前提，2026-09-23 起必须显式声明（TDD-auth-security-hotfix 把两个默认值都收紧了）：
        //  1) 免密登录 —— 生产已改为 fail-closed，未配 sharehub.admin.password 即拒登；
        //  2) 演示种子 —— seed.enabled 默认改为 false，而下面的断言（48 个柜机、CAB1000）吃的就是种子数据。
        properties = {"sharehub.dev-mode.enabled=true", "sharehub.seed.enabled=true"})
class SmokeTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private String token;

    /** 登录换取 ADMIN token（RBAC 上线后 /api、/internal 均需认证；ADMIN 权限=* 且数据范围=ALL）。 */
    private String token() throws Exception {
        if (token == null) {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/auth/login"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"smoke\",\"role\":\"ADMIN\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            token = mapper.readTree(r.body()).get("data").get("token").asText();
        }
        return token;
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return mapper.readTree(resp.body());
    }

    @Test
    void dashboardReturnsWrappedResult() throws Exception {
        JsonNode body = get("/api/ops/dashboard");
        assertThat(body.get("code").asInt()).isEqualTo(0);
        assertThat(body.get("message").asText()).isEqualTo("success");
        assertThat(body.get("data").get("currency").asText()).isEqualTo("AED");
    }

    @Test
    void cabinetsArePagedWithRecords() throws Exception {
        JsonNode data = get("/api/ops/cabinets?page=1&size=10").get("data");
        assertThat(data.get("total").asInt()).isEqualTo(48);
        assertThat(data.get("list")).hasSize(10);
        assertThat(data.get("list").get(0).get("cabinetNo").asText()).isEqualTo("CAB1000");
    }

    @Test
    void vendorsAreListedForGateway() throws Exception {
        JsonNode body = get("/internal/gw/vendors");
        assertThat(body.get("code").asInt()).isEqualTo(0);
        assertThat(body.get("data")).hasSize(3);
    }
}
