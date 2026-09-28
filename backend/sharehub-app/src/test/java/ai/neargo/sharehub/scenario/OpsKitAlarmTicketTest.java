package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.alarm.engine.AlarmEngine;
import ai.neargo.sharehub.common.event.OutboxDispatcher;
import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 告警处置单接 OpsKit（方案 23 §十）：开关 {@code sharehub.flow.alarm-ticket.engine=OPSKIT} 时，告警引擎的联动
 * 变成对 OpsKit 工单的请求。OpsKit 用本机假服务代替：按 dedupeKey 并单、查单返回状态、记录作废 / 并单 / 评论 / 改优先级。
 * 夹具与时间推进照 {@link BusinessAlarmTest}（只判本用例自建的站点）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpsKitAlarmTicketTest extends ApiTestSupport {

    static final String SECRET = "alarm-webhook-secret";
    static final String RUN = Long.toString(System.currentTimeMillis(), 36).toUpperCase();
    static final List<Map<String, Object>> RECEIVED = new CopyOnWriteArrayList<>();
    static final Map<String, String> BY_DEDUPE = new ConcurrentHashMap<>();
    static final AtomicInteger SEQ = new AtomicInteger();
    static final ObjectMapper M = new ObjectMapper();
    static final HttpServer FAKE;

    static {
        try {
            FAKE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        FAKE.createContext("/api/opskit/v1/workspaces/", ex -> {
            String path = ex.getRequestURI().getPath().replaceFirst("^/api/opskit/v1/workspaces/[^/]+", "");
            byte[] in = ex.getRequestBody().readAllBytes();
            Map<String, Object> body = in.length == 0 ? Map.of() : M.readValue(in, Map.class);
            Map<String, Object> rec = new HashMap<>();
            rec.put("method", ex.getRequestMethod());
            rec.put("path", path);
            rec.put("body", body);
            RECEIVED.add(rec);
            Object out;
            int status = 200;
            if (path.startsWith("/tickets/CLOSED-") && path.endsWith("/transitions/cancel")) {
                status = 422;
                out = Map.of("error", "opskit.ticket.closed", "message", "ticket closed");
            } else if (path.startsWith("/tickets/BAD-")) {
                status = 422;
                out = Map.of("error", "opskit.validation", "message", "invalid");
            } else if ("POST".equals(ex.getRequestMethod()) && path.equals("/tickets")) {
                String key = String.valueOf(body.get("dedupeKey"));
                boolean attached = BY_DEDUPE.containsKey(key);
                String no = BY_DEDUPE.computeIfAbsent(key, k -> "AL" + RUN + SEQ.incrementAndGet());
                out = Map.of("ticketNo", no, "attached", attached);
            } else if ("GET".equals(ex.getRequestMethod())) {
                out = Map.of("ticketNo", path.substring("/tickets/".length()), "open", true, "statusCategory", "NEW");
            } else {
                out = Map.of("ok", true);
            }
            byte[] bytes = M.writeValueAsBytes(out);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        FAKE.start();
    }

    @DynamicPropertySource
    static void opskit(DynamicPropertyRegistry r) {
        r.add("sharehub.flow.alarm-ticket.engine", () -> "OPSKIT");
        r.add("sharehub.opskit.base-url", () -> "http://127.0.0.1:" + FAKE.getAddress().getPort());
        r.add("sharehub.opskit.workspace", () -> "ext:MAIN");
        r.add("sharehub.opskit.app-token", () -> "okk_test");
        r.add("sharehub.opskit.webhook-secret", () -> SECRET);
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AlarmEngine engine;
    @Autowired
    OutboxDispatcher dispatcher;
    @Autowired
    ai.neargo.sharehub.integration.opskit.OpsKitTicketCommandExecutor executor;

    final List<String> cabinets = new java.util.ArrayList<>();
    final LocalDateTime t0 = bizToday().atTime(12, 0);

    @BeforeAll
    void warm() {
        login("ADMIN");
    }

    @AfterAll
    void cleanup() {
        ai.neargo.sharehub.support.FixtureCleanup.dropCabinets(jdbc, cabinets);
        FAKE.stop(0);
    }

    @BeforeEach
    void reset() {
        RECEIVED.clear();
    }

    private List<Map<String, Object>> sent(String method, String pathPrefix) {
        return RECEIVED.stream().filter(r -> method.equals(r.get("method")) && String.valueOf(r.get("path")).startsWith(pathPrefix)).toList();
    }

    @Test
    @DisplayName("★★ 整站离线 → 两条告警并成一张「告警工单」（同 dedupeKey）· P1 · 单号回填；恢复后单还没人接 → 作废并写明原因")
    @SuppressWarnings("unchecked")
    void openAttachAndCancelOnRecovery() {
        String site = site();
        cabinet(site, false);
        for (int m = 0; m <= 10; m += 5) engine.tickSites(t0.plusMinutes(m), List.of(site));

        List<Map<String, Object>> creates = sent("POST", "/tickets").stream().filter(r -> r.get("path").equals("/tickets")).toList();
        assertThat(creates).as("两条告警各送一次").hasSize(2);
        Map<String, Object> body = (Map<String, Object>) creates.get(0).get("body");
        assertThat(body.get("typeCode")).isEqualTo("alarm");
        assertThat(body.get("priority")).as("URGENT → P1").isEqualTo("P1");
        assertThat((Map<String, Object>) body.get("fields")).containsKey("alarmCode").containsEntry("siteNo", site);
        assertThat(creates.get(1).get("body")).extracting(b -> ((Map<String, Object>) b).get("dedupeKey")).isEqualTo(body.get("dedupeKey"));
        List<String> refs = jdbc.queryForList("SELECT disposition_ref FROM dev_alarm WHERE site_no=? AND source='EVAL' AND status IN ('OPEN','ACKED')",
                String.class, site);
        assertThat(refs).hasSize(2).doesNotContainNull().containsOnly(refs.get(0));

        RECEIVED.clear();
        jdbc.update("UPDATE dev_cabinet SET last_heartbeat_at=NOW(3), online_status='ONLINE' WHERE site_no=?", site);
        engine.tickSites(t0.plusMinutes(11), List.of(site));
        engine.tickSites(t0.plusMinutes(22), List.of(site));
        assertThat(sent("POST", "/tickets/" + refs.get(0) + "/transitions/cancel")).as("最后一条告警恢复后作废（单还没人接）").hasSize(1);
        assertThat(((Map<String, Object>) sent("POST", "/tickets/" + refs.get(0) + "/transitions/cancel").get(0).get("body")).get("comment"))
                .isEqualTo("关联告警已自动恢复");
    }

    @Test
    @DisplayName("★★ 完工复核：工单以解决结单但告警条件仍成立 → 单上留评论、告警仍开；恢复后再结单 → 告警 RESOLVED 关闭")
    @SuppressWarnings("unchecked")
    void reviewOnClose() throws Exception {
        String site = site();
        cabinet(site, false);
        for (int m = 0; m <= 10; m += 5) engine.tickSites(t0.plusMinutes(m), List.of(site));
        String ticket = jdbc.queryForObject("SELECT disposition_ref FROM dev_alarm WHERE site_no=? AND source='EVAL' AND status='OPEN' LIMIT 1",
                String.class, site);
        String alarmNo = jdbc.queryForObject("SELECT alarm_no FROM dev_alarm WHERE disposition_ref=? LIMIT 1", String.class, ticket);

        RECEIVED.clear();
        assertThat(webhook("ticket.closed", ticket, alarmNo, "1").status).isEqualTo(200);
        List<Map<String, Object>> comments = sent("POST", "/tickets/" + ticket + "/comments");
        assertThat(comments).as("复核未通过写评论").hasSize(1);
        assertThat(String.valueOf(((Map<String, Object>) comments.get(0).get("body")).get("body"))).contains("完工复核未通过");
        assertThat(jdbc.queryForList("SELECT status FROM dev_alarm WHERE disposition_ref=?", String.class, ticket)).doesNotContain("CLOSED");

        jdbc.update("UPDATE dev_cabinet SET last_heartbeat_at=NOW(3), online_status='ONLINE' WHERE site_no=?", site);
        assertThat(webhook("ticket.closed", ticket, alarmNo, "2").status).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT CONCAT(status,'/',close_reason) FROM dev_alarm WHERE disposition_ref=?", String.class, ticket))
                .isNotEmpty().containsOnly("CLOSED/RESOLVED");
    }

    @Test
    @DisplayName("★ 柜级告警已有单、站点级同一轮成立 → 柜级的单改挂到站点级告警（留说明），站点级不再另开新单")
    @SuppressWarnings("unchecked")
    void supersedeRepointsLowerTicket() {
        String site = site();
        cabinet(site, false);
        cabinet(site, true);
        // 单柜借不到成立后 30 分钟才开处置单（V98 告警码配置）：推到 40 分钟
        for (int m = 0; m <= 40; m += 5) engine.tickSites(t0.plusMinutes(m), List.of(site));
        String lower = jdbc.queryForObject("SELECT disposition_ref FROM dev_alarm WHERE site_no=? AND alarm_code='CABINET_UNRENTABLE' AND status='OPEN'",
                String.class, site);
        assertThat(lower).isNotNull();

        RECEIVED.clear();
        jdbc.update("UPDATE dev_cabinet SET last_heartbeat_at=NOW(3) - INTERVAL 1 HOUR WHERE site_no=?", site);
        for (int m = 45; m <= 60; m += 5) engine.tickSites(t0.plusMinutes(m), List.of(site));
        List<String> upperRefs = jdbc.queryForList("SELECT disposition_ref FROM dev_alarm WHERE site_no=? AND alarm_code IN ('SITE_UNRENTABLE','SITE_UNRETURNABLE') AND status='OPEN'",
                String.class, site);
        assertThat(upperRefs).as("站点级告警沿用改挂过来的单（此前会再开一张并覆盖单号，下层那张成孤儿）").isNotEmpty().containsOnly(lower);
        assertThat(sent("POST", "/tickets").stream().filter(r -> r.get("path").equals("/tickets")).toList()).as("没有另开新单").isEmpty();
        assertThat(sent("POST", "/tickets/" + lower + "/comments")).extracting(r -> String.valueOf(((Map<String, Object>) r.get("body")).get("body")))
                .anyMatch(b -> b.startsWith("改挂到上层告警"));
    }

    @Test
    @DisplayName("命令执行器：单已结（422 opskit.ticket.closed）按「已完成」返回、不重投；其他 422 照常抛出进重投")
    void closedTicketIsDone() {
        var closed = new ai.neargo.sharehub.common.event.entity.SysOutbox();
        closed.setEventNo("EVT" + RUN + "C");
        closed.setPayload(ai.neargo.sharehub.common.Json.write(new ai.neargo.sharehub.api.platform.flow.OpsKitTicketCommand(
                "alarm", "ALM-X", "CLOSED-" + RUN, "ALARM_RECOVERED", Map.of("note", "告警自愈"))));
        // GET 返回 NEW → 走作废 → 422 opskit.ticket.closed：不抛
        org.assertj.core.api.Assertions.assertThatCode(() -> executor.deliver(closed)).doesNotThrowAnyException();

        var bad = new ai.neargo.sharehub.common.event.entity.SysOutbox();
        bad.setEventNo("EVT" + RUN + "B");
        bad.setPayload(ai.neargo.sharehub.common.Json.write(new ai.neargo.sharehub.api.platform.flow.OpsKitTicketCommand(
                "alarm", "ALM-Y", "BAD-" + RUN, "COMMENT", Map.of("body", "x"))));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> executor.deliver(bad)).as("其他 422 要重投，不能吞").isInstanceOf(RuntimeException.class);
    }

    private Resp webhook(String type, String ticketNo, String alarmNo, String seq) throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("ticketNo", ticketNo);
        data.put("typeCode", "alarm");
        data.put("subject", Map.of("type", "alarm", "ref", alarmNo));
        data.put("status", "done");
        data.put("statusCategory", "DONE");
        data.put("open", false);
        data.put("seq", Integer.parseInt(seq));
        String body = M.writeValueAsString(Map.of("eventId", UUID.randomUUID().toString(), "eventType", type,
                "idemKey", ticketNo + ":" + seq, "data", data));
        long t = System.currentTimeMillis();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = HexFormat.of().formatHex(mac.doFinal((t + "." + body).getBytes(StandardCharsets.UTF_8)));
        return sendRequest(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/opskit/webhook"))
                .header("Content-Type", "application/json").header("X-OpsKit-Signature", "t=" + t + ",v1=" + sig)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private String site() {
        String site = "STK" + rnd();
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours, region_id) VALUES (?, 'MAIN', ?, ?, 'ACTIVE', '00:00-23:59', 'R-TEST')",
                site, venue, "OpsKit 告警测试站点 " + site);
        jdbc.update("INSERT INTO loc_contract (contract_no, tenant_id, venue_no, site_no, share_rate, start_at, end_at, status) VALUES (?, 'MAIN', ?, ?, 0.2, ?, ?, 'ACTIVE')",
                "CTK" + rnd(), venue, site, bizToday().minusDays(1).toString(), bizToday().plusYears(1).toString());
        return site;
    }

    private String cabinet(String site, boolean online) {
        String cab = "CBK" + rnd();
        cabinets.add(cab);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, site_no, slot_total, available_count, status,"
                        + " online_status, last_heartbeat_at) VALUES (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, 8, 1, 'DEPLOYED', 'ONLINE', "
                        + (online ? "NOW(3)" : "NOW(3) - INTERVAL 1 HOUR") + ")",
                cab, cab, site);
        return cab;
    }

    private static String rnd() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
