package ai.neargo.sharehub.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    // ——————————————————————— 翻页 ———————————————————————

    /**
     * 分页接口的页大小上限，本仓库统一是 200（各 service 的 {@code Math.min(size, 200)}）。
     */
    private static final int PAGE_SIZE = 200;

    /** 翻页上限，纯兜底 —— 接口返回异常的 total 时不至于死循环。 */
    private static final int MAX_PAGES = 50;

    /**
     * 翻页找一条记录，找不到返回 {@code null}。
     *
     * <h3>为什么不能 {@code page=1&size=200} 了事</h3>
     * <b>测试库是累积的</b>（见 application.properties）。「取第一页再在里面找」
     * 这种写法在表小的时候一直是对的，越过页大小的那天忽然开始失败 ——
     * 而失败的样子是<b>「刚建的东西查不到」</b>，看起来像功能坏了，
     * 实际是分页没够着。本轮为此查错方向过好几次：
     * 分润对账、代理商档案、合同列表、场地方、站点、申请单。
     *
     * <h3>为什么也不能靠 keyword 过滤</h3>
     * 各列表的 keyword 匹配哪些列**各不相同**：代理商/提现/申请单匹配业务号，
     * 而场地方只匹配名称、站点只匹配名称与区域、合同只匹配场地方名与站点名。
     * 按编号传进去一条都匹配不上 —— 而「过滤后空列表」与「确实没有」长得一模一样。
     *
     * @param path  列表端点，可以自带查询参数（如 {@code "...?status=APPLY"}）
     * @param field 用哪个字段比对（如 {@code "orderNo"}）
     * @param value 要找的值
     */
    protected JsonNode findInPages(String path, String field, String value, String token) {
        String sep = path.contains("?") ? "&" : "?";
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode body = get(path + sep + "page=" + page + "&size=" + PAGE_SIZE, token).okData();
            for (JsonNode row : body.path("list")) {
                if (value.equals(row.path(field).asText())) return row;
            }
            if ((long) page * PAGE_SIZE >= body.path("total").asLong()) return null;
        }
        throw new AssertionError("翻了 " + MAX_PAGES + " 页还没到头：" + path
                + " —— 接口的 total 不对，还是真有这么多数据？");
    }

    /**
     * 翻完所有页。用于「对全量做聚合」的断言 ——
     * 只取第一页去和另一个全量数字比，差的那部分会被当成业务错误报出来。
     */
    protected List<JsonNode> pageAll(String path, String token) {
        String sep = path.contains("?") ? "&" : "?";
        List<JsonNode> out = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode body = get(path + sep + "page=" + page + "&size=" + PAGE_SIZE, token).okData();
            JsonNode list = body.path("list");
            if (list.isEmpty()) return out;
            list.forEach(out::add);
            if (out.size() >= body.path("total").asLong()) return out;
        }
        throw new AssertionError("翻了 " + MAX_PAGES + " 页还没到头：" + path);
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

    /** 自定义请求（multipart 上传等）走同一套响应解析。 */
    protected Resp sendRequest(HttpRequest req) {
        return send(req);
    }

    /** 经文件服务上传（multipart），返回 FileRef。合同签署、工单完工等要绑定真实文件的流程用。 */
    protected JsonNode uploadFile(String category, String name, byte[] bytes, String token) {
        String boundary = "----sharehub" + java.util.UUID.randomUUID();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"category\"\r\n\r\n" + category + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        out.writeBytes(head.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.writeBytes(bytes);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/platform/files"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        return send(req).okData();
    }

    /** 最小合法 PDF（过文件头嗅探）。 */
    protected static byte[] minimalPdf() {
        return "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
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
