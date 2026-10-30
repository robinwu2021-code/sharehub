package ai.neargo.sharehub.inv.remote;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阶段 0 新封装的库存服务接口（查回、出库池多条件、单据详情、数量实盘、仓库编辑 / 停用）：
 * 用本机假服务核对路径、方法、参数、幂等键与 404 的处理，契约以 ai-shop inventory-svc 的 Controller 为准。
 */
class HttpInventoryApiQueryTest {

    static HttpServer server;
    static HttpInventoryApi api;
    /** 「方法 路径?查询」→ 请求体；同时记下幂等键。 */
    static final Map<String, String> seen = new ConcurrentHashMap<>();
    static final Map<String, String> keys = new ConcurrentHashMap<>();
    /** 「方法 路径」→ 请求头（统一客户端那条路要核的头）。 */
    static final Map<String, com.sun.net.httpserver.Headers> headers = new ConcurrentHashMap<>();

    static final Map<String, String[]> replies = Map.ofEntries(
            Map.entry("GET /api/inventory/v1/commands/REQ-1", new String[]{"200",
                    "{\"commandType\":\"dispatch.claim\",\"resultBody\":\"{\\\"claimNo\\\":\\\"CL1\\\"}\",\"createdAt\":\"2026-10-30T08:00:00\"}"}),
            Map.entry("GET /api/inventory/v1/commands/REQ-MISSING", new String[]{"404",
                    "{\"code\":\"inventory.not_found\",\"message\":\"NOT_FOUND\",\"data\":{\"args\":[\"command\"]}}"}),
            Map.entry("GET /api/inventory/v1/commands/REQ-BROKEN", new String[]{"500", "{\"code\":\"inventory.internal\"}"}),
            Map.entry("GET /api/inventory/v1/dispatch-pool/claims/by-request/REQ-1", new String[]{"200",
                    "{\"claimNo\":\"CL1\",\"dispatchIds\":[\"DP1\",\"DP2\"]}"}),
            Map.entry("GET /api/inventory/v1/dispatch-pool/claims/by-request/REQ-MISSING", new String[]{"404",
                    "{\"code\":\"inventory.not_found\",\"data\":{\"args\":[\"claim\"]}}"}),
            Map.entry("GET /api/inventory/v1/dispatch-pool", new String[]{"200",
                    "[{\"dispatchId\":\"DP1\",\"outboundNo\":\"OUT1\",\"itemId\":\"ITM1\",\"serialNo\":\"CAB1\",\"qty\":1,\"locationId\":\"LOC1\","
                            + "\"status\":\"AVAILABLE\",\"parentDispatchId\":null,\"rootTargetType\":\"MERCHANT\",\"rootTargetRef\":\"AG010\","
                            + "\"targets\":{\"MERCHANT\":{\"ref\":\"AG010\",\"nameSnapshot\":\"x\"}},\"resourceVersion\":3}]"}),
            Map.entry("GET /api/inventory/v1/in-transit", new String[]{"200",
                    "[{\"rootTargetType\":\"MERCHANT\",\"rootTargetRef\":\"AG010\",\"itemId\":\"ITM1\",\"qty\":2}]"}),
            Map.entry("GET /api/inventory/v1/inbounds/IN1", new String[]{"200",
                    "{\"docNo\":\"IN1\",\"kind\":\"IN\",\"status\":\"POSTED\",\"purpose\":\"OPENING\",\"locationId\":\"LOC1\",\"totalQty\":1,"
                            + "\"profileVersion\":2,\"lines\":[{\"lineNo\":1,\"itemId\":\"ITM1\",\"qty\":1,\"gateRef\":null,\"parentSerialNo\":null}],"
                            + "\"serials\":[{\"lineNo\":1,\"itemId\":\"ITM1\",\"serialNo\":\"CAB1\",\"parentSerialNo\":null,\"gateRef\":\"QC1\"}],"
                            + "\"targets\":[]}"}),
            Map.entry("GET /api/inventory/v1/outbounds/OUT1", new String[]{"200",
                    "{\"docNo\":\"OUT1\",\"kind\":\"OUT\",\"status\":\"POSTED\",\"purpose\":\"ISSUE\",\"locationId\":\"LOC1\",\"totalQty\":1,"
                            + "\"profileVersion\":2,\"lines\":[],\"serials\":[],\"targets\":[{\"type\":\"MERCHANT\",\"ref\":\"AG010\",\"nameSnapshot\":\"x\"}]}"}),
            Map.entry("PUT /api/inventory/v1/counts/CNT1/lines/ITM1", new String[]{"200", "{\"docNo\":\"CNT1\"}"}),
            Map.entry("GET /api/inventory/v1/counts/CNT-COUNTING", new String[]{"200", countJson("CNT-COUNTING", "COUNTING")}),
            Map.entry("GET /api/inventory/v1/counts/CNT-VOIDED", new String[]{"200", countJson("CNT-VOIDED", "VOIDED")}),
            Map.entry("GET /api/inventory/v1/counts/CNT-SUBMITTED", new String[]{"200", countJson("CNT-SUBMITTED", "SUBMITTED")}),
            Map.entry("PATCH /api/inventory/v1/warehouses/LOC1", new String[]{"200", "{\"locationId\":\"LOC1\"}"}),
            Map.entry("POST /api/inventory/v1/outbounds", new String[]{"200", "{\"docNo\":\"OUT9\"}"}),
            Map.entry("POST /api/inventory/v1/outbounds/OUT-REJ/post", new String[]{"409",
                    "{\"code\":\"inventory.serial.conflict\",\"data\":{\"args\":[\"CAB1:not_in_stock\"]}}"}),
            Map.entry("POST /api/inventory/v1/outbounds/OUT-ODD/post", new String[]{"409", "{\"code\":\"inventory.something_new\"}"}),
            Map.entry("POST /api/inventory/v1/warehouses/LOC1/disable", new String[]{"200", "{\"locationId\":\"LOC1\"}"}));

    static String countJson(String no, String status) {
        return "{\"countNo\":\"" + no + "\",\"locationId\":\"LOC1\",\"status\":\"" + status + "\",\"contentVersion\":0,"
                + "\"approvedVersion\":null,\"submittedBy\":null,\"reviewer\":null,\"lines\":[],\"exceptions\":[],\"missingSerials\":[]}";
    }

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/inventory/v1", ex -> {
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getRawQuery();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String key = ex.getRequestMethod() + " " + path;
            seen.put(key + (query == null ? "" : "?" + query), body);
            String idem = ex.getRequestHeaders().getFirst("Idempotency-Key");
            if (idem != null) keys.put(key, idem);
            headers.put(key, ex.getRequestHeaders());
            String[] r = replies.getOrDefault(key, new String[]{"404", "{\"status\":404,\"error\":\"Not Found\"}"});
            byte[] out = r[1].getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(Integer.parseInt(r[0]), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        api = new HttpInventoryApi(new StaticInventoryEndpoint(base), "k", "s", Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Test
    void commandLookupReturnsStoredResult() {
        InventoryApi.StoredCommand c = api.command("REQ-1").orElseThrow();
        assertThat(c.commandType()).isEqualTo("dispatch.claim");
        assertThat(c.resultBody()).contains("CL1");
        assertThat(c.createdAt()).isNotNull();
    }

    @Test
    void lookupNotFoundIsEmptyButOtherErrorsThrow() {
        assertThat(api.command("REQ-MISSING")).isEmpty();
        assertThat(api.claimByRequest("REQ-MISSING")).isEmpty();
        assertThatThrownBy(() -> api.command("REQ-BROKEN")).isInstanceOf(InventoryRemoteException.class)
                .satisfies(e -> assertThat(((InventoryRemoteException) e).code).isEqualTo("inventory.internal"));
    }

    @Test
    void claimByRequest() {
        assertThat(api.claimByRequest("REQ-1").orElseThrow().dispatchIds()).containsExactly("DP1", "DP2");
    }

    @Test
    void dispatchPoolSendsRepeatedParams() {
        List<InventoryApi.DispatchView> v = api.dispatchPool(new InventoryApi.DispatchFilter(
                List.of("AVAILABLE", "CLAIMED"), List.of("AG010"), null, null, null, null, "ITM1"));
        assertThat(v).singleElement().satisfies(d -> {
            assertThat(d.rootTargetRef()).isEqualTo("AG010");
            assertThat(d.resourceVersion()).isEqualTo(3);
        });
        assertThat(seen).containsKey("GET /api/inventory/v1/dispatch-pool?status=AVAILABLE&status=CLAIMED&rootTargetRef=AG010&itemId=ITM1");
        api.dispatchesBySerial("CAB1");
        assertThat(seen).as("按序列号查走同一接口").containsKey("GET /api/inventory/v1/dispatch-pool?serialNo=CAB1");
    }

    @Test
    void inTransit() {
        assertThat(api.inTransit()).singleElement().satisfies(t -> assertThat(t.qty()).isEqualByComparingTo("2"));
    }

    @Test
    void documentDetails() {
        InventoryApi.Document in = api.inbound("IN1");
        assertThat(in.purpose()).isEqualTo("OPENING");
        assertThat(in.serials()).singleElement().satisfies(s -> assertThat(s.gateRef()).isEqualTo("QC1"));
        assertThat(api.outbound("OUT1").targets()).singleElement().satisfies(t -> assertThat(t.ref()).isEqualTo("AG010"));
    }

    @Test
    void countStatusIsMappedToHostVocabulary() {
        assertThat(api.count("CNT-COUNTING").status()).as("库存服务的盘点中").isEqualTo("OPEN");
        assertThat(api.count("CNT-VOIDED").status()).as("库存服务的作废").isEqualTo("CANCELLED");
        assertThat(api.count("CNT-SUBMITTED").status()).isEqualTo("SUBMITTED");
    }

    @Test
    void countLineIsPutWithKey() {
        api.countLine("CNT1", "ITM1", 5, "count-line-1");
        assertThat(seen.get("PUT /api/inventory/v1/counts/CNT1/lines/ITM1")).contains("\"countedQty\":5");
        assertThat(keys.get("PUT /api/inventory/v1/counts/CNT1/lines/ITM1")).isEqualTo("count-line-1");
    }

    @Test
    void writesThroughInventoryClientCarryKeyActorTraceAndCredential() {
        org.slf4j.MDC.put(ai.neargo.sharehub.trace.TraceContext.MDC_KEY, "TRACE-1");
        try {
            String no = api.createOutbound("LOC1", "ISSUE", null, Map.of("MERCHANT", "AG010"), null, List.of(), List.of("CAB1"), "issue-SH1");
            assertThat(no).isEqualTo("OUT9");
        } finally {
            org.slf4j.MDC.remove(ai.neargo.sharehub.trace.TraceContext.MDC_KEY);
        }
        assertThat(seen.get("POST /api/inventory/v1/outbounds")).contains("\"locationId\":\"LOC1\"").contains("\"serialNos\":[\"CAB1\"]")
                .contains("\"MERCHANT\":\"AG010\"");
        com.sun.net.httpserver.Headers h = headers.get("POST /api/inventory/v1/outbounds");
        assertThat(h.getFirst("Idempotency-Key")).isEqualTo("issue-SH1");
        assertThat(h.getFirst("X-Inventory-Actor")).isEqualTo("SYSTEM");
        assertThat(h.getFirst("X-Trace-Id")).isEqualTo("TRACE-1");
        assertThat(h.getFirst("Authorization")).isEqualTo("Bearer k:s");
        assertThat(h.getFirst("X-Inventory-Owner-Ref")).as("账本凭证：不带账本头").isNull();
    }

    @Test
    void clientErrorsKeepStatusForKnownCodesAndAreUnknownOtherwise() {
        assertThatThrownBy(() -> api.postOutbound("OUT-REJ", "post-REJ")).isInstanceOf(InventoryRemoteException.class).satisfies(e -> {
            InventoryRemoteException r = (InventoryRemoteException) e;
            assertThat(r.status).as("明确拒绝：恢复日志记 FAILED").isEqualTo(409);
            assertThat(r.code).isEqualTo("inventory.serial.conflict");
            assertThat(r.args).containsExactly("CAB1:not_in_stock");
        });
        assertThatThrownBy(() -> api.postOutbound("OUT-ODD", "post-ODD")).isInstanceOf(InventoryRemoteException.class).satisfies(e -> {
            InventoryRemoteException r = (InventoryRemoteException) e;
            assertThat(r.status).as("客户端认不出的错误码：结果不明，留给查回").isEqualTo(503);
            assertThat(r.code).isEqualTo("inventory.unreachable");
        });
    }

    @Test
    void warehouseUpdateUsesPatchAndDisable() {
        api.updateWarehouse("LOC1", "迪拜仓", "DXB", "wh-upd-1");
        assertThat(seen.get("PATCH /api/inventory/v1/warehouses/LOC1")).contains("\"name\":\"迪拜仓\"").contains("\"regionRef\":\"DXB\"");
        api.disableWarehouse("LOC1", "wh-dis-1");
        assertThat(keys.get("POST /api/inventory/v1/warehouses/LOC1/disable")).isEqualTo("wh-dis-1");
    }
}
