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

    /**
     * 分页 + 种子完好。
     *
     * <p>⚠️ <b>total 不能断绝对数</b>：{@code test_sharehub} 是有状态的共享库
     * （见 {@code application.properties} 的说明），别的用例建的柜机会永久留在表里。
     * 此前这里写 {@code isEqualTo(48)}，9-25 某次跑留下 13 台随机号柜机之后
     * 它就一直红着 —— <b>而那次跑自己是绿的</b>，红的是另外两条用例，一天没人认领。
     * 对可变共享状态断绝对数量，等于给未来的每一次无关改动都埋一颗噪音。
     *
     * <p>所以改断两件本方法名下真正该断的事：分页算术（total 是全表数、单页给满 size 条）
     * 与种子基线完好（{@code CAB1000}–{@code CAB1047} 共 48 台还在）。
     */
    @Test
    void cabinetsArePagedWithRecords() throws Exception {
        JsonNode data = get("/api/ops/cabinets?page=1&size=10").get("data");
        assertThat(data.get("total").asInt())
                .as("全表数至少是 48 台种子；多出来的是别的用例留在共享库里的")
                .isGreaterThanOrEqualTo(SEED_CABINETS);
        assertThat(data.get("list")).hasSize(10);
        assertThat(data.get("list").get(0).get("cabinetNo").asText()).isEqualTo("CAB1000");

        // 种子基线：keyword 只 like cabinet_no / location_name，CAB1 恰好圈住那 48 台
        JsonNode seed = get("/api/ops/cabinets?keyword=CAB1&page=1&size=100").get("data");
        assertThat(seed.get("total").asInt())
                .as("种子被删/被改就该红 —— 这才是这条断言原本想守的东西")
                .isEqualTo(SEED_CABINETS);
    }

    /** 种子柜机台数（CAB1000–CAB1047）。 */
    private static final int SEED_CABINETS = 48;

    @Test
    void vendorsAreListedForGateway() throws Exception {
        JsonNode body = get("/internal/gw/vendors");
        assertThat(body.get("code").asInt()).isEqualTo(0);
        assertThat(body.get("data")).hasSize(3);
    }
}
