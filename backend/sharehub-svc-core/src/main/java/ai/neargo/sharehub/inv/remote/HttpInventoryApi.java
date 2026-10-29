package ai.neargo.sharehub.inv.remote;

import ai.neargo.sharehub.auth.SecurityUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link InventoryApi} 的 HTTP 实现（S1.1）。鉴权：{@code Authorization: Bearer appKey:appSecret}（租户由凭证决定，
 * 本系统 = 进销存里的一个 owner）；操作人进 {@code X-Inventory-Actor}；写操作带 {@code Idempotency-Key}。
 *
 * <p>成功体是裸 JSON；失败体 {@code {code, message, data:{args}}}，翻成 {@link InventoryRemoteException}。
 * 超时短：库存服务挂了不能拖住运营的请求，失败即整笔回滚（调用方同事务里还没写本地）。
 */
@Component
public class HttpInventoryApi implements InventoryApi {

    private static final String V1 = "/api/inventory/v1";

    /** 与项目其他处一致用静态实例：Boot 4 默认不注册 Jackson 2 的 ObjectMapper Bean。容忍对方新增字段。 */
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RestClient http;
    private final ObjectMapper json = JSON;
    private final String baseUrl;
    private final String appKey;
    private final String appSecret;

    public HttpInventoryApi(@Value("${sharehub.inventory.base-url:}") String baseUrl,
                            @Value("${sharehub.inventory.app-key:}") String appKey,
                            @Value("${sharehub.inventory.app-secret:}") String appSecret,
                            @Value("${sharehub.inventory.connect-timeout:2s}") Duration connectTimeout,
                            @Value("${sharehub.inventory.read-timeout:5s}") Duration readTimeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.appKey = appKey;
        this.appSecret = appSecret;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) connectTimeout.toMillis());
        f.setReadTimeout((int) readTimeout.toMillis());
        this.http = RestClient.builder().requestFactory(f).build();
    }

    @Override
    public boolean configured() {
        return !baseUrl.isBlank() && !appKey.isBlank() && !appSecret.isBlank();
    }

    // —— 主数据 ——

    @Override
    public String upsertItem(String externalRef, ItemSpec spec) {
        return call(HttpMethod.PUT, V1 + "/items/by-ref/" + externalRef, spec, key("item", externalRef)).path("itemId").asText();
    }

    @Override
    public void upsertTarget(String type, String ref, String name, String parentRef) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("parentRef", parentRef);
        body.put("status", "ACTIVE");
        call(HttpMethod.PUT, V1 + "/external-targets/" + type + "/" + ref, body, key("target", type + ":" + ref + ":" + name + ":" + parentRef));
    }

    @Override
    public List<Warehouse> warehouses() {
        return read(call(HttpMethod.GET, V1 + "/warehouses", null, null), new TypeReference<>() { });
    }

    @Override
    public String createWarehouse(String name, String idempotencyKey) {
        return call(HttpMethod.POST, V1 + "/warehouses", Map.of("name", name), idempotencyKey).path("locationId").asText();
    }

    // —— 入库 ——

    @Override
    public String createInbound(String locationId, String purpose, String sourceRef, String remark, List<InboundLine> lines,
                                String idempotencyKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("locationId", locationId);
        body.put("purpose", purpose);
        body.put("sourceRef", sourceRef);
        body.put("remark", remark);
        body.put("lines", lines);
        return call(HttpMethod.POST, V1 + "/inbounds", body, idempotencyKey).path("docNo").asText();
    }

    @Override
    public void postInbound(String docNo, String idempotencyKey) {
        call(HttpMethod.POST, V1 + "/inbounds/" + docNo + "/post", Map.of(), idempotencyKey);
    }

    @Override
    public void businessClearance(String docNo, String idempotencyKey) {
        call(HttpMethod.POST, V1 + "/inbounds/" + docNo + "/business-clearance", Map.of(), idempotencyKey);
    }

    // —— 出库与出库池 ——

    @Override
    public String createOutbound(String locationId, String purpose, Map<String, String> targets, String remark, List<String> serialNos,
                                 String idempotencyKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("locationId", locationId);
        body.put("purpose", purpose);
        body.put("targets", targets);
        body.put("remark", remark);
        body.put("serialNos", serialNos);
        return call(HttpMethod.POST, V1 + "/outbounds", body, idempotencyKey).path("docNo").asText();
    }

    @Override
    public void postOutbound(String docNo, String idempotencyKey) {
        call(HttpMethod.POST, V1 + "/outbounds/" + docNo + "/post", Map.of(), idempotencyKey);
    }

    @Override
    public List<DispatchView> dispatchesBySerial(String serialNo) {
        String path = UriComponentsBuilder.fromPath(V1 + "/dispatch-pool").queryParam("serialNo", serialNo).build().toUriString();
        return read(call(HttpMethod.GET, path, null, null), new TypeReference<>() { });
    }

    @Override
    public Claim claim(List<String> dispatchIds, String businessRef, String idempotencyKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("dispatchIds", dispatchIds);
        body.put("businessRef", businessRef);
        return read(call(HttpMethod.POST, V1 + "/dispatch-pool/claims", body, idempotencyKey), new TypeReference<>() { });
    }

    @Override
    public void releaseClaim(String claimNo, String idempotencyKey) {
        call(HttpMethod.POST, V1 + "/dispatch-pool/claims/" + claimNo + "/release", Map.of(), idempotencyKey);
    }

    @Override
    public void redirect(String dispatchId, Map<String, String> targets, String reason, String idempotencyKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("targets", targets);
        body.put("reason", reason);
        call(HttpMethod.POST, V1 + "/dispatch-pool/" + dispatchId + "/redirect", body, idempotencyKey);
    }

    @Override
    public void revoke(String dispatchId, String reason, String idempotencyKey) {
        call(HttpMethod.POST, V1 + "/dispatch-pool/" + dispatchId + "/revoke", Map.of("reason", reason), idempotencyKey);
    }

    // —— 流水与盘点 ——

    @Override
    public List<LedgerEntry> ledger(String itemId, String locationId, String docNo, Long afterId, int limit) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath(V1 + "/ledger").queryParam("limit", limit);
        if (itemId != null) b.queryParam("itemId", itemId);
        if (locationId != null) b.queryParam("locationId", locationId);
        if (docNo != null) b.queryParam("docNo", docNo);
        if (afterId != null) b.queryParam("afterId", afterId);
        return read(call(HttpMethod.GET, b.build().toUriString(), null, null), new TypeReference<>() { });
    }

    @Override
    public String openCount(String locationId, List<String> itemIds, String idempotencyKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("locationId", locationId);
        body.put("itemIds", itemIds);
        return call(HttpMethod.POST, V1 + "/counts", body, idempotencyKey).path("docNo").asText();
    }

    @Override
    public CountView count(String countNo) {
        return read(call(HttpMethod.GET, V1 + "/counts/" + countNo, null, null), new TypeReference<>() { });
    }

    @Override
    public List<CountScan> scanCount(String countNo, List<String> serialNos, String idempotencyKey) {
        return read(call(HttpMethod.POST, V1 + "/counts/" + countNo + "/scans", Map.of("serialNos", serialNos), idempotencyKey),
                new TypeReference<>() { });
    }

    @Override
    public void submitCount(String countNo, String idempotencyKey) {
        call(HttpMethod.POST, V1 + "/counts/" + countNo + "/submit", Map.of(), idempotencyKey);
    }

    @Override
    public void approveCount(String countNo, String note, String idempotencyKey) {
        call(HttpMethod.POST, V1 + "/counts/" + countNo + "/approve", noteBody(note), idempotencyKey);
    }

    @Override
    public void rejectCount(String countNo, String note, String idempotencyKey) {
        call(HttpMethod.POST, V1 + "/counts/" + countNo + "/reject", noteBody(note), idempotencyKey);
    }

    @Override
    public CountPosted postCount(String countNo, String idempotencyKey) {
        return read(call(HttpMethod.POST, V1 + "/counts/" + countNo + "/post", Map.of(), idempotencyKey), new TypeReference<>() { });
    }

    @Override
    public void cancelCount(String countNo, String idempotencyKey) {
        call(HttpMethod.POST, V1 + "/counts/" + countNo + "/cancel", Map.of(), idempotencyKey);
    }

    private static Map<String, Object> noteBody(String note) {
        Map<String, Object> m = new HashMap<>();
        m.put("note", note);
        return m;
    }

    @Override
    public List<PeriodRow> periodSummary(LocalDateTime from, LocalDateTime to, String locationId) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath(V1 + "/reports/period-summary")
                .queryParam("from", from.toString()).queryParam("to", to.toString());
        if (locationId != null) b.queryParam("locationId", locationId);
        return read(call(HttpMethod.GET, b.build().toUriString(), null, null), new TypeReference<>() { });
    }

    // —— 事件 ——

    @Override
    public EventPage pullEvents(Long afterSeq, int limit) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath(V1 + "/events").queryParam("limit", limit);
        if (afterSeq != null) b.queryParam("afterSeq", afterSeq);
        return read(call(HttpMethod.GET, b.build().toUriString(), null, null), new TypeReference<>() { });
    }

    @Override
    public void ackEvents(long seq) {
        call(HttpMethod.POST, V1 + "/event-acks", Map.of("seq", seq), "ack-" + seq);
    }

    // —— 内部 ——

    /** 主数据 upsert 的幂等键按内容定：同内容重放无害，内容变了换键。 */
    private static String key(String kind, String content) {
        return kind + "-" + Integer.toHexString(content.hashCode()) + "-" + content.length();
    }

    private JsonNode call(HttpMethod method, String path, Object body, String idempotencyKey) {
        if (!configured()) {
            throw new InventoryRemoteException(503, "inventory.not_configured", List.of(), "库存服务未配置（sharehub.inventory.*）", null);
        }
        try {
            RestClient.RequestBodySpec req = http.method(method).uri(baseUrl + path)
                    .header("Authorization", "Bearer " + appKey + ":" + appSecret)
                    .accept(MediaType.APPLICATION_JSON);
            String actor = SecurityUtils.userNo();
            req.header("X-Inventory-Actor", actor == null ? "SYSTEM" : "STAFF:" + actor);
            if (idempotencyKey != null) req.header("Idempotency-Key", idempotencyKey.length() > 64 ? idempotencyKey.substring(0, 64) : idempotencyKey);
            if (body != null) req.contentType(MediaType.APPLICATION_JSON).body(body);
            String raw = req.retrieve().body(String.class);
            return raw == null || raw.isBlank() ? json.createObjectNode() : json.readTree(raw);
        } catch (RestClientResponseException e) {
            throw rejected(e, path);
        } catch (InventoryRemoteException e) {
            throw e;
        } catch (Exception e) {
            throw new InventoryRemoteException(503, "inventory.unreachable", List.of(), "库存服务不可达：" + path, e);
        }
    }

    private InventoryRemoteException rejected(RestClientResponseException e, String path) {
        String code = "inventory.http_" + e.getStatusCode().value();
        List<String> args = new ArrayList<>();
        try {
            JsonNode b = json.readTree(e.getResponseBodyAsString());
            if (b.hasNonNull("code")) code = b.get("code").asText();
            b.path("data").path("args").forEach(a -> args.add(a.asText()));
        } catch (Exception ignored) {
            // 非 JSON 错误体：按 HTTP 状态码兜底
        }
        return new InventoryRemoteException(e.getStatusCode().value(), code, args, "库存服务拒绝 " + path + "：" + code + " " + args, e);
    }

    private <T> T read(JsonNode n, TypeReference<T> type) {
        return json.convertValue(n, type);
    }
}
