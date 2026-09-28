package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报障转工单 → OpsKit 工单（M5-3）：开关 {@code sharehub.flow.cs-ticket.engine=OPSKIT} 时，
 * 「转工单」发到 OpsKit 开「设备报修」单，工单号回填到报障单。OpsKit 用本机假服务代替。
 */
class OpsKitCsTicketTest extends ApiTestSupport {

    static final List<Map<String, Object>> RECEIVED = new CopyOnWriteArrayList<>();
    static final AtomicInteger SEQ = new AtomicInteger(0);
    static final String RUN = Long.toString(System.currentTimeMillis(), 36).toUpperCase();
    static final ObjectMapper M = new ObjectMapper();
    static final HttpServer FAKE;

    static {
        try {
            FAKE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        FAKE.createContext("/api/opskit/v1/workspaces/", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            Map<String, Object> rec = new HashMap<>();
            rec.put("path", ex.getRequestURI().getPath());
            rec.put("idem", ex.getRequestHeaders().getFirst("Idempotency-Key"));
            rec.put("body", M.readValue(in, Map.class));
            RECEIVED.add(rec);
            byte[] out = M.writeValueAsBytes(Map.of("ticketNo", "RP" + RUN + SEQ.incrementAndGet(), "attached", false));
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        FAKE.start();
    }

    @DynamicPropertySource
    static void opskit(DynamicPropertyRegistry r) {
        r.add("sharehub.flow.cs-ticket.engine", () -> "OPSKIT");
        r.add("sharehub.opskit.base-url", () -> "http://127.0.0.1:" + FAKE.getAddress().getPort());
        r.add("sharehub.opskit.workspace", () -> "ext:MAIN");
        r.add("sharehub.opskit.app-token", () -> "okk_test");
    }

    @AfterAll
    static void stop() {
        FAKE.stop(0);
    }

    @BeforeEach
    void reset() {
        RECEIVED.clear();
    }

    @Test
    @DisplayName("★★ 转工单 → OpsKit 开「设备报修」单（来源 / 来源单号 / 同机柜去重键 / 幂等键）；工单号回填；重复转用同一幂等键")
    @SuppressWarnings("unchecked")
    void toWorkOrderCreatesOpsKitTicket() {
        String admin = login("ADMIN");
        Map<String, Object> m = new HashMap<>();
        m.put("issue", "M5-3 探针：借不出");
        m.put("channel", "PHONE");   // 渠道是受控词表（CsTicketChannel，d1f9a6b3 起）
        m.put("cabinetNo", "CB-M5-" + RUN);
        String no = post("/api/ops/cs/tickets", m, admin).okData().path("ticketNo").asText();

        post("/api/ops/cs/tickets/" + no + "/work-order", Map.of(), admin).okData();
        assertThat(RECEIVED).as("提交后立即送 OpsKit").hasSize(1);
        Map<String, Object> r = RECEIVED.get(0);
        assertThat(r.get("path")).isEqualTo("/api/opskit/v1/workspaces/ext:MAIN/tickets");
        assertThat(r.get("idem")).isEqualTo("opskit:ticket:cs_ticket:" + no);
        Map<String, Object> body = (Map<String, Object>) r.get("body");
        assertThat(body.get("typeCode")).isEqualTo("repair");
        assertThat((Map<String, Object>) body.get("fields")).containsEntry("sourceRef", no);
        assertThat(body.get("dedupeKey")).isEqualTo("cs:cabinet:CB-M5-" + RUN);
        assertThat((Map<String, Object>) body.get("subject")).containsEntry("type", "cs_ticket").containsEntry("ref", no);

        JsonNode t = get("/api/ops/cs/tickets/" + no, admin).okData();
        assertThat(t.path("woNo").asText()).as("OpsKit 工单号回填到报障单").startsWith("RP" + RUN);
        assertThat(t.path("status").asText()).isEqualTo("PROCESSING");

        // 已有工单号 → 幂等闸门直接返回，不再送第二次
        post("/api/ops/cs/tickets/" + no + "/work-order", Map.of(), admin).okData();
        assertThat(RECEIVED).as("已回填的单不再重复送").hasSize(1);
    }
}
