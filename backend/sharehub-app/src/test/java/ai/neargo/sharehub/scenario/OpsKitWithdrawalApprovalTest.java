package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.common.event.OutboxDispatcher;
import ai.neargo.sharehub.common.event.entity.SysOutbox;
import ai.neargo.sharehub.common.event.mapper.SysOutboxMapper;
import ai.neargo.sharehub.support.ApiTestSupport;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提现送 OpsKit 审批（M5-1 试点，方案 23 §9.4）的端到端：送审请求体 · webhook 回写 · 幂等 · 失败与补救。
 *
 * <p>OpsKit 用本机一个假服务代替（只认 {@code POST /api/opskit/v1/workspaces/{ws}/instances}），
 * 记下收到的请求；回写用与 OpsKit {@code SignatureSpec} 同一算法签名的 webhook 模拟。
 *
 * <p>负对照（2026-09-28 实测）：拆掉 {@code OutboxEventBus} 的跨进程投递修复后，
 * 「送审请求体」用例红 —— 假 OpsKit 收不到任何请求（此前 OutboxConsumer 只在进程内投递失败时才被调用）。
 */
class OpsKitWithdrawalApprovalTest extends ApiTestSupport {

    static final String SECRET = "webhook-test-secret";
    static final List<Map<String, Object>> RECEIVED = new CopyOnWriteArrayList<>();
    static final AtomicInteger FAIL_WITH = new AtomicInteger(0);   // 非 0 = 假 OpsKit 回这个状态码
    static final AtomicInteger SEQ = new AtomicInteger(0);
    /** 审批单号每次运行唯一：去重记录落在共享测试库里，号重复会被当成「上次已处理」直接跳过（9-28 实测） */
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
            rec.put("auth", ex.getRequestHeaders().getFirst("Authorization"));
            rec.put("idem", ex.getRequestHeaders().getFirst("Idempotency-Key"));
            rec.put("body", M.readValue(in, Map.class));
            RECEIVED.add(rec);
            int fail = FAIL_WITH.get();
            byte[] out = fail != 0
                    ? "{\"error\":\"opskit.unavailable\"}".getBytes(StandardCharsets.UTF_8)
                    : M.writeValueAsBytes(Map.of("instanceNo", "PI-" + RUN + SEQ.incrementAndGet(), "approvalNo", "AP-" + RUN + "-" + SEQ.get()));
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(fail != 0 ? fail : 200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        FAKE.start();
    }

    @DynamicPropertySource
    static void opskit(DynamicPropertyRegistry r) {
        r.add("sharehub.flow.withdrawal.engine", () -> "OPSKIT");
        r.add("sharehub.opskit.base-url", () -> "http://127.0.0.1:" + FAKE.getAddress().getPort());
        r.add("sharehub.opskit.workspace", () -> "ext:MAIN");
        r.add("sharehub.opskit.app-token", () -> "okk_test");
        r.add("sharehub.opskit.webhook-secret", () -> SECRET);
    }

    @AfterAll
    static void stop() {
        FAKE.stop(0);
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SysOutboxMapper outbox;
    @Autowired
    OutboxDispatcher dispatcher;

    @BeforeEach
    void reset() {
        RECEIVED.clear();
        FAIL_WITH.set(0);
    }

    /**
     * 收尾：本类建的单停在「待审 + OpsKit 审批中」，而测试库是共享的 ——
     * 别的用例取「任意一笔待审提现」时会拿到它、被 409 拦下（9-28 全量实测：PayoutAccountTest 两条因此红）。
     * approval_round 只有 OPSKIT 模式会写，据此认出本类的行（也顺带清掉此前几轮的残留）。
     */
    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        jdbc.update("UPDATE stl_withdrawal SET status='FAILED', reject_reason='测试收尾', approval_state=NULL "
                + "WHERE approval_round IS NOT NULL AND status='APPLY'");
        jdbc.update("UPDATE stl_withdrawal SET approval_state=NULL WHERE approval_round IS NOT NULL AND approval_state IS NOT NULL");
    }

    private static String randomPayee() {
        return "AGT" + (100_000 + ThreadLocalRandom.current().nextInt(800_000));
    }

    private String apply(String admin, String payee) {
        Map<String, Object> acct = new HashMap<>();
        acct.put("payeeType", "AGENT");
        acct.put("payeeNo", payee);
        acct.put("bankCode", "ENBD");
        acct.put("accountName", "M5 试点");
        acct.put("accountMasked", "AE07033123456789012****");
        acct.put("currency", "AED");
        post("/api/trade/payout-accounts", acct, admin).okData();
        Map<String, Object> req = new HashMap<>();
        req.put("payeeType", "AGENT");
        req.put("payeeNo", payee);
        req.put("payeeName", "M5 试点");
        req.put("amount", 120);
        req.put("currency", "AED");
        req.put("bankCode", "ENBD");
        return post("/api/trade/withdrawals", req, admin).okData().path("withdrawNo").asText();
    }

    private JsonNode row(String admin, String no) {
        for (JsonNode r : get("/api/trade/withdrawals?keyword=" + no + "&size=5", admin).okData().path("list")) {
            if (no.equals(r.path("withdrawNo").asText())) return r;
        }
        throw new AssertionError("找不到提现 " + no);
    }

    private Resp webhook(String approvalNo, String no, String result, String reason, String sigSecret) throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("approvalNo", approvalNo);
        data.put("instanceNo", "PI-X");
        data.put("roundNo", 1);
        data.put("result", result);
        data.put("reason", reason);
        data.put("decidedBy", Map.of("ref", "mb_fin", "externalRef", "fin.lead", "displayName", "Fatima 财务"));
        data.put("subject", Map.of("type", "withdrawal", "ref", no));
        Map<String, Object> env = Map.of("eventId", "ev-" + approvalNo, "eventType", "approval.completed",
                "idemKey", approvalNo + ":1", "data", data);
        String body = M.writeValueAsString(env);
        long t = System.currentTimeMillis();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/opskit/webhook"))
                .header("Content-Type", "application/json")
                .header("X-OpsKit-Signature", "t=" + t + ",v1=" + hmac(sigSecret, t + "." + body))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return sendRequest(req);
    }

    static String hmac(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("★★ 申请即送审：幂等键 · 业务对象 · 代理范围标签 · 代提交人；回填审批单号进「审批中」")
    @SuppressWarnings("unchecked")
    void applySubmitsToOpsKit() {
        String admin = login("ADMIN");
        String payee = randomPayee();
        String no = apply(admin, payee);

        assertThat(RECEIVED).as("提交后应立即送审（跨进程投递修复）").hasSize(1);
        Map<String, Object> r = RECEIVED.get(0);
        assertThat(r.get("path")).isEqualTo("/api/opskit/v1/workspaces/ext:MAIN/instances");
        assertThat(r.get("auth")).isEqualTo("Bearer okk_test");
        assertThat(r.get("idem")).isEqualTo("opskit:withdrawal:" + no + ":1");
        Map<String, Object> body = (Map<String, Object>) r.get("body");
        assertThat(body.get("processCode")).isEqualTo("withdrawal_audit");
        assertThat((Map<String, Object>) body.get("subject")).containsEntry("type", "withdrawal").containsEntry("ref", no);
        assertThat((List<String>) body.get("scopes")).containsExactly("agent:" + payee);
        assertThat(((Map<String, Object>) body.get("onBehalfOf")).get("externalRef")).as("代提交人").isNotNull();

        JsonNode w = row(admin, no);
        assertThat(w.path("approvalState").asText()).isEqualTo("IN_REVIEW");
        assertThat(w.path("approvalNo").asText()).startsWith("AP-" + RUN);
        assertThat(w.path("status").asText()).isEqualTo("APPLY");
    }

    @Test
    @DisplayName("★★ 审批中的单不能在业务页审（409）；通过回写 → 放款中，审批人 = OpsKit 里做决定的人；重复消息只推进一次")
    void approvedWebhookAdvancesOnce() throws Exception {
        String admin = login("ADMIN");
        String no = apply(admin, randomPayee());
        String ap = row(admin, no).path("approvalNo").asText();

        assertThat(post("/api/trade/withdrawals/" + no + "/audit", Map.of("approve", true), admin).status)
                .as("两处都能点「通过」一定打架").isEqualTo(409);

        Resp first = webhook(ap, no, "APPROVED", null, SECRET);
        assertThat(first.status).isEqualTo(200);
        assertThat(first.body.path("handled").asBoolean()).isTrue();
        JsonNode w = row(admin, no);
        assertThat(w.path("status").asText()).isEqualTo("PAYING");
        assertThat(w.path("auditorName").asText()).isEqualTo("Fatima 财务");
        assertThat(w.path("approvalState").isNull() || w.path("approvalState").isMissingNode()).isTrue();
        assertThat(w.path("approvalNo").asText()).as("结束后保留单号便于追溯").isEqualTo(ap);
        assertThat(jdbc.queryForObject("SELECT auditor_no FROM stl_withdrawal WHERE withdraw_no=?", String.class, no))
                .isEqualTo("fin.lead");

        Resp again = webhook(ap, no, "APPROVED", null, SECRET);
        assertThat(again.status).isEqualTo(200);
        assertThat(again.body.path("handled").asBoolean()).as("同一 idemKey 第二次不再处理").isFalse();
        assertThat(row(admin, no).path("status").asText()).isEqualTo("PAYING");
    }

    @Test
    @DisplayName("★ 坏签名 401 且不动业务；驳回回写带原因 → 提现失败")
    void badSignatureAndReject() throws Exception {
        String admin = login("ADMIN");
        String no = apply(admin, randomPayee());
        String ap = row(admin, no).path("approvalNo").asText();

        assertThat(webhook(ap, no, "APPROVED", null, "wrong-secret").status).isEqualTo(401);
        assertThat(row(admin, no).path("status").asText()).as("验签失败不能动业务").isEqualTo("APPLY");

        assertThat(webhook(ap, no, "REJECTED", "金额与对账单不符", SECRET).status).isEqualTo(200);
        JsonNode w = row(admin, no);
        assertThat(w.path("status").asText()).isEqualTo("FAILED");
        assertThat(w.path("rejectReason").asText()).isEqualTo("金额与对账单不符");
    }

    @Test
    @DisplayName("★ 通过了但生效不了（收款账户被删）→ 已通过未生效；补齐后重试生效，审批人仍是原决定人")
    void approvedButBlockedThenRetry() throws Exception {
        String admin = login("ADMIN");
        String payee = randomPayee();
        String no = apply(admin, payee);
        String ap = row(admin, no).path("approvalNo").asText();
        jdbc.update("DELETE FROM stl_payout_account WHERE payee_no=?", payee);

        assertThat(webhook(ap, no, "APPROVED", null, SECRET).status).as("业务挡住也不让 OpsKit 重投").isEqualTo(200);
        JsonNode w = row(admin, no);
        assertThat(w.path("approvalState").asText()).isEqualTo("APPROVED_BLOCKED");
        assertThat(w.path("status").asText()).isEqualTo("APPLY");
        assertThat(w.path("approvalNote").asText()).contains("收款账户");

        assertThat(post("/api/trade/withdrawals/" + no + "/approval/retry-effect", Map.of(), admin).status)
                .as("没补齐时重试仍被拦").isEqualTo(400);
        Map<String, Object> acct = new HashMap<>();
        acct.put("payeeType", "AGENT");
        acct.put("payeeNo", payee);
        acct.put("bankCode", "ENBD");
        acct.put("accountName", "M5 试点");
        acct.put("accountMasked", "AE07033123456789012****");
        acct.put("currency", "AED");
        post("/api/trade/payout-accounts", acct, admin).okData();
        JsonNode done = post("/api/trade/withdrawals/" + no + "/approval/retry-effect", Map.of(), admin).okData();
        assertThat(done.path("status").asText()).isEqualTo("PAYING");
        assertThat(done.path("auditorName").asText()).isEqualTo("Fatima 财务");
    }

    @Test
    @DisplayName("★ OpsKit 不可用 → 重投到上限转死信 → 送审失败；重新送审用新轮次的幂等键")
    void submitFailureThenResubmit() {
        String admin = login("ADMIN");
        FAIL_WITH.set(503);
        String no = apply(admin, randomPayee());
        assertThat(row(admin, no).path("approvalState").asText()).isEqualTo("SUBMITTING");

        for (int i = 0; i < 10; i++) {
            SysOutbox o = outbox.selectOne(new LambdaQueryWrapper<SysOutbox>()
                    .eq(SysOutbox::getEventType, "APPROVAL_REQUESTED").eq(SysOutbox::getAggregateId, no)
                    .orderByDesc(SysOutbox::getId).last("LIMIT 1"));
            if ("DEAD".equals(o.getStatus())) break;
            dispatcher.dispatchOne(o);
        }
        JsonNode w = row(admin, no);
        assertThat(w.path("approvalState").asText()).isEqualTo("SUBMIT_FAILED");
        assertThat(w.path("approvalNote").asText()).contains("503");

        FAIL_WITH.set(0);
        RECEIVED.clear();
        JsonNode r = post("/api/trade/withdrawals/" + no + "/approval/resubmit", Map.of(), admin).okData();
        assertThat(r.path("withdrawNo").asText()).isEqualTo(no);
        assertThat(RECEIVED).hasSize(1);
        assertThat(RECEIVED.get(0).get("idem")).isEqualTo("opskit:withdrawal:" + no + ":2");
        assertThat(row(admin, no).path("approvalState").asText()).isEqualTo("IN_REVIEW");
    }
}
