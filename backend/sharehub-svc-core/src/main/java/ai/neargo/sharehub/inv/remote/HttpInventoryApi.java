package ai.neargo.sharehub.inv.remote;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.client.InventoryClient;
import ai.neargo.inventory.client.InventoryHostApi;
import ai.neargo.inventory.client.InventoryReservationClient;
import ai.neargo.svc.client.ServiceClientSpec;
import ai.neargo.svc.client.ServiceClients;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@link InventoryApi} 的实现（S1.1）。鉴权：{@code Authorization: Bearer appKey:appSecret}（账本凭证，账本由凭证决定，
 * 本系统 = 进销存里的一本账）；操作人进 {@code X-Inventory-Actor}；写操作带 {@code Idempotency-Key}。
 *
 * <p><b>优先走库存服务提供的 {@link InventoryClient}</b>（ai-shop《进销存独立服务-客户端调用文档》）：
 * 主数据、仓库、入库、出库、出库池（按状态 / 商家 / 序列号）、领取、撤回、单据详情、命令查回、事件。
 * 客户端还没有的接口（仓库编辑停用、业务放行、按内部号的余额与序列号、出库池其余筛选、释放领取、调整、流水、盘点、
 * 期间汇总、按请求号查领取）仍直连 —— 已作为宿主接口需求 #15 提给库存服务，补齐后逐个换掉。
 *
 * <p>两条路的错误都翻成 {@link InventoryRemoteException}：库存服务认得出的业务错误带原 HTTP 状态与错误码
 * （4xx = 明确拒绝，恢复日志记 FAILED）；连不上、超时、熔断中、认不出的错误体一律 503 {@code inventory.unreachable}
 * （结果不明，留给查回）。超时短：库存服务挂了不能拖住运营的请求。
 */
@Component
public class HttpInventoryApi implements InventoryApi {

    private static final String V1 = "/api/inventory/v1";

    /** 与项目其他处一致用静态实例：Boot 4 默认不注册 Jackson 2 的 ObjectMapper Bean。容忍对方新增字段。 */
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RestClient http;
    private final ObjectMapper json = JSON;
    /** 寻址（固定地址 / 服务发现），每次调用前解析。 */
    private final InventoryEndpoint endpoint;
    private final String appKey;
    private final String appSecret;
    /** 统一客户端：单例、线程安全；与直连共用地址与凭证。熔断：连续 5 次传输失败后打开 10 秒。 */
    private final InventoryClient client;

    public HttpInventoryApi(InventoryEndpoint endpoint,
                            @Value("${sharehub.inventory.app-key:}") String appKey,
                            @Value("${sharehub.inventory.app-secret:}") String appSecret,
                            @Value("${sharehub.inventory.connect-timeout:2s}") Duration connectTimeout,
                            @Value("${sharehub.inventory.read-timeout:5s}") Duration readTimeout) {
        this.endpoint = endpoint;
        this.appKey = appKey;
        this.appSecret = appSecret;
        // JDK HttpClient：仓库编辑要 PATCH，HttpURLConnection 不支持；固定 HTTP/1.1，免得对明文地址做 h2c 升级协商
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(client);
        f.setReadTimeout(readTimeout);
        this.http = RestClient.builder().requestFactory(f).build();
        this.client = InventoryClient.of(
                ServiceClients.create(InventoryHostApi.class, clientSpec(connectTimeout, readTimeout)),
                InventoryReservationClient.create(this::currentBaseUrl, appKey, appSecret, connectTimeout, readTimeout, 5, Duration.ofSeconds(10)));
    }

    /**
     * 与客户端自带的规格相同（凭证、超时、错误体翻译），另挂链路号 —— 客户端的 create 不收拦截器，所以自己拼一份
     * 再用 {@link InventoryClient#of}。
     */
    private ServiceClientSpec clientSpec(Duration connectTimeout, Duration readTimeout) {
        ClientHttpRequestInterceptor trace = (request, body, execution) -> {
            // 链路号：两边日志能按同一个号对上（对接方案 §3.1）
            String id = org.slf4j.MDC.get(ai.neargo.sharehub.trace.TraceContext.MDC_KEY);
            if (id != null && !id.isBlank()) request.getHeaders().set("X-Trace-Id", id);
            return execution.execute(request, body);
        };
        return new ServiceClientSpec("inventory", s -> currentBaseUrl(), "Authorization",
                () -> appKey.isBlank() ? null : "Bearer " + appKey + ":" + appSecret, true, connectTimeout, readTimeout,
                "sharehub.inventory.base-url", "sharehub.inventory.app-key",
                List.of(InventoryReservationClient::decodeErrors, trace));
    }

    private Optional<String> currentBaseUrl() {
        return endpoint.configured() ? Optional.ofNullable(endpoint.baseUrl()) : Optional.empty();
    }

    @Override
    public boolean configured() {
        return endpoint.configured() && !appKey.isBlank() && !appSecret.isBlank();
    }

    // —— 主数据 ——

    @Override
    public String upsertItem(String externalRef, ItemSpec spec) {
        return via("items/by-ref/" + externalRef, s -> s.upsertItem(masterDataKey("item"), externalRef, new InventoryHostApi.ItemSpec(
                spec.name(), spec.baseUom(), spec.trackingMode(), spec.gatePolicy(), spec.gateRefType(), spec.attributes())));
    }

    @Override
    public void upsertTarget(String type, String ref, String name, String parentRef) {
        via("external-targets/" + type + "/" + ref, s -> s.syncTarget(masterDataKey("target"), type, ref,
                new InventoryHostApi.TargetSync(name, parentRef, "ACTIVE", null, null, null)));
    }

    @Override
    public List<Warehouse> warehouses() {
        return via("warehouses", s -> s.warehouses(null)).stream()
                .map(w -> new Warehouse(w.locationId(), w.name(), w.regionRef(), w.status(), w.externalRef())).toList();
    }

    @Override
    public String createWarehouse(String name, String externalRef, String idempotencyKey) {
        return via("warehouses", s -> s.createWarehouse(key(idempotencyKey),
                new InventoryHostApi.WarehouseCreate(name, blankToNull(externalRef), null)));
    }

    @Override
    public void updateWarehouse(String locationId, String name, String regionRef, String idempotencyKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("regionRef", regionRef);
        call(HttpMethod.PATCH, V1 + "/warehouses/" + locationId, body, idempotencyKey);
    }

    @Override
    public void disableWarehouse(String locationId, String idempotencyKey) {
        call(HttpMethod.POST, V1 + "/warehouses/" + locationId + "/disable", Map.of(), idempotencyKey);
    }

    // —— 入库 ——

    @Override
    public String createInbound(String locationId, String purpose, String sourceRef, String basis, List<String> attachmentRefs, String remark,
                                List<InboundLine> lines, String idempotencyKey) {
        List<InventoryHostApi.InLine> in = lines.stream().map(l -> new InventoryHostApi.InLine(l.itemId(), null, l.qty(), l.gateRef(),
                l.serials() == null ? null : l.serials().stream()
                        .map(x -> new InventoryHostApi.SerialIn(x.serialNo(), x.gateRef(), x.parentSerialNo())).toList())).toList();
        return via("inbounds", s -> s.createInbound(key(idempotencyKey), new InventoryHostApi.InboundCreate(locationId, null, purpose,
                sourceRef, blankToNull(basis), attachmentRefs == null || attachmentRefs.isEmpty() ? null : attachmentRefs, remark, in)));
    }

    @Override
    public void postInbound(String docNo, String idempotencyKey) {
        run("inbounds/" + docNo + "/post", s -> s.postInbound(key(idempotencyKey), docNo));
    }

    @Override
    public void businessClearance(String docNo, String idempotencyKey) {
        call(HttpMethod.POST, V1 + "/inbounds/" + docNo + "/business-clearance", Map.of(), idempotencyKey);
    }

    // —— 出库与出库池 ——

    @Override
    public String createOutbound(String locationId, String purpose, String reasonCode, Map<String, String> targets, String remark,
                                 List<OutboundLine> lines, List<String> serialNos, String idempotencyKey) {
        List<InventoryHostApi.OutLine> out = lines == null ? List.of()
                : lines.stream().map(l -> new InventoryHostApi.OutLine(l.itemId(), null, l.qty(), l.parentSerialNo())).toList();
        return via("outbounds", s -> s.createOutbound(key(idempotencyKey), new InventoryHostApi.OutboundCreate(locationId, null, purpose,
                reasonCode, targets == null ? Map.of() : targets, remark, out, serialNos == null ? List.of() : serialNos)));
    }

    @Override
    public void postOutbound(String docNo, String idempotencyKey) {
        run("outbounds/" + docNo + "/post", s -> s.postOutbound(key(idempotencyKey), docNo));
    }

    @Override
    public List<Balance> balances(String locationId, String itemId) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath(V1 + "/balances");
        if (locationId != null) b.queryParam("locationId", locationId);
        if (itemId != null) b.queryParam("itemId", itemId);
        return read(call(HttpMethod.GET, b.build().toUriString(), null, null), new TypeReference<>() { });
    }

    @Override
    public List<InStock> serials(String itemId, String locationId) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath(V1 + "/serials");
        if (itemId != null) b.queryParam("itemId", itemId);
        if (locationId != null) b.queryParam("locationId", locationId);
        return read(call(HttpMethod.GET, b.build().toUriString(), null, null), new TypeReference<>() { });
    }

    @Override
    public List<DispatchView> dispatchPool(DispatchFilter f) {
        if (f.targetType() == null && f.targetRefs() == null && f.outboundNo() == null && f.itemId() == null) {
            // 客户端只收状态、商家、序列号三项；其余筛选仍直连（需求 #15）
            return read(tree(via("dispatch-pool", s -> s.dispatchPool(f.statuses(), f.rootTargetRefs(), f.serialNo()))),
                    new TypeReference<>() { });
        }
        UriComponentsBuilder b = UriComponentsBuilder.fromPath(V1 + "/dispatch-pool");
        if (f.statuses() != null) f.statuses().forEach(v -> b.queryParam("status", v));
        if (f.rootTargetRefs() != null) f.rootTargetRefs().forEach(v -> b.queryParam("rootTargetRef", v));
        if (f.targetType() != null) b.queryParam("targetType", f.targetType());
        if (f.targetRefs() != null) f.targetRefs().forEach(v -> b.queryParam("targetRef", v));
        if (f.outboundNo() != null) b.queryParam("outboundNo", f.outboundNo());
        if (f.serialNo() != null) b.queryParam("serialNo", f.serialNo());
        if (f.itemId() != null) b.queryParam("itemId", f.itemId());
        return read(call(HttpMethod.GET, b.encode().build().toUriString(), null, null), new TypeReference<>() { });
    }

    @Override
    public List<InTransit> inTransit() {
        return via("in-transit", InventoryClient.Session::inTransit).stream()
                .map(t -> new InTransit(t.rootTargetType(), t.rootTargetRef(), t.itemId(), t.qty())).toList();
    }

    @Override
    public Claim claim(List<String> dispatchIds, String businessRef, String idempotencyKey) {
        InventoryHostApi.Claim c = via("dispatch-pool/claims", s -> s.claim(key(idempotencyKey), dispatchIds, businessRef));
        return new Claim(c.claimNo(), c.dispatchIds());
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
        via("dispatch-pool/" + dispatchId + "/revoke", s -> s.revoke(key(idempotencyKey), dispatchId, reason));
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
        CountView v = read(call(HttpMethod.GET, V1 + "/counts/" + countNo, null, null), new TypeReference<>() { });
        String st = REMOTE_COUNT_STATUS.getOrDefault(v.status(), v.status());
        return st.equals(v.status()) ? v : new CountView(v.countNo(), v.locationId(), st, v.contentVersion(), v.approvedVersion(),
                v.submittedBy(), v.reviewer(), v.lines(), v.exceptions(), v.missingSerials());
    }

    /** 库存服务盘点状态 → 宿主词表（其余同名）。 */
    private static final Map<String, String> REMOTE_COUNT_STATUS = Map.of("COUNTING", "OPEN", "VOIDED", "CANCELLED");

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

    @Override
    public void countLine(String countNo, String itemId, int countedQty, String idempotencyKey) {
        call(HttpMethod.PUT, V1 + "/counts/" + countNo + "/lines/" + itemId, Map.of("countedQty", countedQty), idempotencyKey);
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
        return read(tree(via("events", s -> s.events(afterSeq, limit))), new TypeReference<>() { });
    }

    @Override
    public void ackEvents(long seq) {
        run("event-acks", s -> s.ackEvents(seq));
    }

    // —— 单据详情与查回 ——

    @Override
    public Document inbound(String docNo) {
        return read(tree(via("inbounds/" + docNo, s -> s.inbound(docNo))), new TypeReference<>() { });
    }

    @Override
    public Document outbound(String docNo) {
        return read(tree(via("outbounds/" + docNo, s -> s.outbound(docNo))), new TypeReference<>() { });
    }

    @Override
    public Optional<StoredCommand> command(String requestId) {
        return absentIfNotFound(() -> read(tree(via("commands/" + requestId, s -> s.command(key(requestId)))), new TypeReference<>() { }));
    }

    @Override
    public Optional<Claim> claimByRequest(String requestId) {
        return absentIfNotFound(() -> read(call(HttpMethod.GET, V1 + "/dispatch-pool/claims/by-request/" + requestId, null, null),
                new TypeReference<>() { }));
    }

    // —— 内部 ——

    /** 查回类接口：库存服务明确答「没有这条」（404 inventory.not_found）才算空，其他错误照常抛。 */
    private static <T> Optional<T> absentIfNotFound(Supplier<T> lookup) {
        try {
            return Optional.of(lookup.get());
        } catch (InventoryRemoteException e) {
            if (e.status == 404 && "inventory.not_found".equals(e.code)) return Optional.empty();
            throw e;
        }
    }

    /**
     * 主数据 upsert（物料、目标）是按 ref 的 PUT，重放无副作用，每次一个新键即可。
     * 不能用按内容算的固定键：库存服务按请求体算指纹，它给请求体加字段（如 M9 给目标加 linkedOwner）后，
     * 同一个固定键在命令保留期内会一直冲突（inventory.command.conflict），升级后商家就再也同步不上。
     */
    private static String masterDataKey(String kind) {
        return kind + "-" + UUID.randomUUID();
    }

    /** 走统一客户端：本次请求的操作人建会话（会话只是三个字符串，开销可忽略），错误翻成 {@link InventoryRemoteException}。 */
    private <T> T via(String path, java.util.function.Function<InventoryClient.Session, T> op) {
        requireConfigured();
        try {
            return op.apply(client.self(InventoryActor.current()));
        } catch (InventoryException e) {
            throw translate(e, path);
        }
    }

    private void run(String path, java.util.function.Consumer<InventoryClient.Session> op) {
        via(path, s -> {
            op.accept(s);
            return null;
        });
    }

    private InventoryRemoteException translate(InventoryException e, String path) {
        List<String> args = e.args() == null ? List.of() : Arrays.stream(e.args()).map(String::valueOf).toList();
        if (e.error() == InventoryError.UNAVAILABLE) {
            // 连不上 / 超时 / 熔断中 / 认不出的错误体：结果不明。熔断中请求没发出，不算地址故障
            if (!args.contains("circuit_open")) currentBaseUrl().ifPresent(base -> endpoint.failed(base, e));
            return new InventoryRemoteException(503, "inventory.unreachable", args, "库存服务不可达：" + path + " " + args, e);
        }
        return new InventoryRemoteException(e.error().httpStatus(), e.error().key(), args,
                "库存服务拒绝 " + path + "：" + e.error().key() + " " + args, e);
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new InventoryRemoteException(503, "inventory.not_configured", List.of(), "库存服务未配置（sharehub.inventory.*）", null);
        }
    }

    /** 客户端的结构多变应答是 Jackson 3 的树，转成本类用的 Jackson 2 再按记录读。 */
    private JsonNode tree(Object node) {
        try {
            return node == null ? json.createObjectNode() : json.readTree(node.toString());
        } catch (Exception e) {
            throw new InventoryRemoteException(503, "inventory.unreadable", List.of(), "库存服务应答无法解析", e);
        }
    }

    /** 命令键 ≤ 64 字符（库存服务约定） */
    private static String key(String idempotencyKey) {
        return idempotencyKey == null || idempotencyKey.length() <= 64 ? idempotencyKey : idempotencyKey.substring(0, 64);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private JsonNode call(HttpMethod method, String path, Object body, String idempotencyKey) {
        requireConfigured();
        String base = endpoint.baseUrl();
        try {
            RestClient.RequestBodySpec req = http.method(method).uri(base + path)
                    .header("Authorization", "Bearer " + appKey + ":" + appSecret)
                    .accept(MediaType.APPLICATION_JSON);
            req.header("X-Inventory-Actor", InventoryActor.current());
            // 链路号：两边日志能按同一个号对上（对接方案 §3.1：宿主原先没发）
            String trace = org.slf4j.MDC.get(ai.neargo.sharehub.trace.TraceContext.MDC_KEY);
            if (trace != null && !trace.isBlank()) req.header("X-Trace-Id", trace);
            if (idempotencyKey != null) req.header("Idempotency-Key", key(idempotencyKey));
            if (body != null) req.contentType(MediaType.APPLICATION_JSON).body(body);
            String raw = req.retrieve().body(String.class);
            return raw == null || raw.isBlank() ? json.createObjectNode() : json.readTree(raw);
        } catch (RestClientResponseException e) {
            throw rejected(e, path);
        } catch (InventoryRemoteException e) {
            throw e;
        } catch (Exception e) {
            endpoint.failed(base, e);
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
