package ai.neargo.opskit.connector;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 宿主系统调 OpsKit 的客户端（21/07 X1）。应用凭证 {@code okk_…}；空间用宿主租户号（{@code ext:MAIN}）。
 *
 * <p>三件接入方最容易做错的事，这里替你做掉：
 * <ol>
 *   <li><b>重投拿同一个结果</b>：写请求都带 {@code Idempotency-Key}，请求体按固定顺序序列化（OpsKit 判「同内容」按字节）；</li>
 *   <li><b>代提交人</b>：{@link OnBehalfOf} 放进请求体，审批单上显示真实提交人、自审禁止才判得准；</li>
 *   <li><b>错误可判</b>：非 2xx 抛 {@link OpsKitCallException}，带错误键、逐字段错误与 traceId，并告诉你能否重试。</li>
 * </ol>
 * 线程安全，整个应用共用一个实例。
 */
public final class OpsKitConnector {

    /** 代成员：宿主里的人。不存在即由 OpsKit 开通；groups / scopes 与令牌声明同义。 */
    public record OnBehalfOf(String externalRef, String displayName, Set<String> groups, Set<String> scopes) {
        public OnBehalfOf {
            Objects.requireNonNull(externalRef, "externalRef");
        }

        public static OnBehalfOf of(String externalRef, String displayName) {
            return new OnBehalfOf(externalRef, displayName, null, null);
        }
    }

    /** 业务对象：审批单 / 工单挂在它上面，对账按它查。 */
    public record Subject(String type, String ref, Map<String, Object> display) {
        public static Subject of(String type, String ref, String title) {
            return new Subject(type, ref, title == null ? null : Map.of("title", title));
        }
    }

    public record StartInstance(String processCode, Subject subject, Map<String, Object> fields, Set<String> scopes, OnBehalfOf onBehalfOf) {
    }

    /** @param replayed true = 同一 Idempotency-Key 的重放（拿到的是首次结果） */
    public record Started(String instanceNo, String status, String statusCategory, boolean open, int seq, String approvalNo, boolean replayed) {
    }

    /** @param first false = 重复回复，result 是首次回复的结论 */
    public record Replied(String instanceNo, String result, boolean first) {
    }

    private final HttpClient http;
    private final String base;
    private final String credential;
    private final Duration timeout;
    private final ObjectMapper json = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private OpsKitConnector(Builder b) {
        this.http = HttpClient.newBuilder().connectTimeout(b.timeout).build();
        this.base = b.baseUrl.replaceAll("/+$", "") + "/workspaces/" + enc(b.workspace);
        this.credential = b.credential;
        this.timeout = b.timeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String baseUrl;
        private String workspace;
        private String credential;
        private Duration timeout = Duration.ofSeconds(10);

        /** 如 {@code http://127.0.0.1:8090/api/opskit/v1} */
        public Builder baseUrl(String v) {
            baseUrl = v;
            return this;
        }

        /** {@code ext:<宿主租户号>} 或 {@code ws_…} */
        public Builder workspace(String v) {
            workspace = v;
            return this;
        }

        /** 应用凭证 {@code okk_…}：从密钥管理 / 环境变量读，不要写进代码 */
        public Builder credential(String v) {
            credential = v;
            return this;
        }

        public Builder timeout(Duration v) {
            timeout = v;
            return this;
        }

        public OpsKitConnector build() {
            Objects.requireNonNull(baseUrl, "baseUrl");
            Objects.requireNonNull(workspace, "workspace");
            if (credential == null || !credential.startsWith("okk_")) {
                throw new IllegalArgumentException("credential must be an application credential (okk_…)");
            }
            return new OpsKitConnector(this);
        }
    }

    /**
     * 发起实例（送审）。{@code idempotencyKey} 建议 {@code opskit:<业务>:<业务号>:<轮次>}：同一轮重投拿同一个实例，
     * 驳回后重新提交换下一轮。
     */
    public Started start(StartInstance req, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("processCode", req.processCode());
        if (req.subject() != null) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", req.subject().type());
            s.put("ref", req.subject().ref());
            if (req.subject().display() != null) {
                s.put("display", req.subject().display());
            }
            body.put("subject", s);
        }
        body.put("fields", req.fields() == null ? Map.of() : req.fields());
        if (req.scopes() != null && !req.scopes().isEmpty()) {
            body.put("scopes", req.scopes().stream().sorted().toList());
        }
        if (req.onBehalfOf() != null) {
            body.put("onBehalfOf", onBehalf(req.onBehalfOf()));
        }
        Result r = send("POST", "/instances", body, requireKey(idempotencyKey));
        JsonNode n = r.body();
        return new Started(n.path("instanceNo").asString(), n.path("status").asString(), n.path("statusCategory").asString(), n.path("open").asBoolean(),
                n.path("seq").asInt(), n.hasNonNull("approvalNo") ? n.get("approvalNo").asString() : null, r.replayed());
    }

    /**
     * 回复外部确认（收到 {@code instance.awaiting_confirmation} 后）。重复回复不会再触发迁移，返回首次结论 ——
     * 所以网络超时后放心重试。拒绝须写原因。
     */
    public Replied replyConfirmation(String token, boolean confirm, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", confirm ? "CONFIRM" : "REJECT");
        if (reason != null) {
            body.put("reason", reason);
        }
        // 不带幂等键：回复本身在服务端就是幂等的（首次结论为准）
        JsonNode n = send("POST", "/confirmations/" + enc(token), body, null).body();
        return new Replied(n.path("instanceNo").asString(), n.path("result").asString(), n.path("first").asBoolean());
    }

    /** 某业务对象上的全部实例、审批与流转（对账用，见 {@link Reconciler}）。 */
    public JsonNode subjectHistory(String type, String ref) {
        return send("GET", "/subjects/" + enc(type) + "/" + enc(ref) + "/history", null, null).body();
    }

    /** 兜底：调任意接口（路径相对空间，如 {@code /tickets}）。写请求请带幂等键。 */
    public JsonNode call(String method, String path, Object body, String idempotencyKey) {
        return send(method, path, body, idempotencyKey).body();
    }

    private record Result(JsonNode body, boolean replayed) {
    }

    private Result send(String method, String path, Object body, String idempotencyKey) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout).header("Authorization", "Bearer " + credential)
                .header("Accept", "application/json");
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        }
        HttpResponse<String> res;
        try {
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new OpsKitCallException(0, "connector.network", e.getMessage(), List.of(), null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpsKitCallException(0, "connector.interrupted", "interrupted", List.of(), null, e);
        }
        JsonNode n = res.body() == null || res.body().isBlank() ? json.createObjectNode() : json.readTree(res.body());
        if (res.statusCode() / 100 != 2) {
            throw OpsKitCallException.from(res.statusCode(), n);
        }
        return new Result(n, res.headers().firstValue("Idempotent-Replayed").map("true"::equals).orElse(false));
    }

    private static Map<String, Object> onBehalf(OnBehalfOf o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("externalRef", o.externalRef());
        if (o.displayName() != null) {
            m.put("displayName", o.displayName());
        }
        if (o.groups() != null) {
            m.put("groups", o.groups().stream().sorted().toList());
        }
        if (o.scopes() != null) {
            m.put("scopes", o.scopes().stream().sorted().toList());
        }
        return m;
    }

    private static String requireKey(String k) {
        if (k == null || k.isBlank() || k.length() > 128) {
            throw new IllegalArgumentException("idempotencyKey must be 1..128 chars");
        }
        return k;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
