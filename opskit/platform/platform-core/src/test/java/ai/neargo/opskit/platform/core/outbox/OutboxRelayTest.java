package ai.neargo.opskit.platform.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.event.DomainEvent;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.platform.api.account.CreateWorkspaceRequest;
import ai.neargo.opskit.platform.api.app.AppSecrets;
import ai.neargo.opskit.platform.api.app.CreateAppRequest;
import ai.neargo.opskit.platform.api.webhook.SignatureSpec;
import ai.neargo.opskit.platform.core.support.DirectoryEnv;
import ai.neargo.opskit.platform.store.dao.OutboxDao;
import ai.neargo.opskit.platform.store.dao.WebhookDao;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O1-9：签名 · 至少一次 · 退避 · 部分成功不重发 · 耗尽与重投 · 订阅过滤。 */
class OutboxRelayTest {

    record TestEvent(TenantContext tenant, String eventType, String idemKey, Instant occurredAt, Object data) implements DomainEvent {
    }

    /** 声明了成员字段的事件：信封里展开成 {ref, externalRef, displayName}。 */
    record MemberEvent(TenantContext tenant, String eventType, String idemKey, Instant occurredAt, Object data) implements DomainEvent {
        @Override
        public java.util.Set<String> memberFields() {
            return java.util.Set.of("actor", "decidedBy", "absent");
        }
    }

    /** 本地接收方：按预设状态码应答，并记下收到的请求。 */
    static final class Receiver implements AutoCloseable {
        final HttpServer server;
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final List<String> signatures = new CopyOnWriteArrayList<>();
        final AtomicInteger status = new AtomicInteger(200);

        Receiver() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/hook", ex -> {
                bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                signatures.add(ex.getRequestHeaders().getFirst(SignatureSpec.HEADER));
                ex.sendResponseHeaders(status.get(), -1);
                ex.close();
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    record Env(DirectoryEnv d, OutboxWriter writer, OutboxRelay relay, WebhookDao webhooks, OutboxDao outbox) {
        void publish(TestEvent e) {
            d.f.tx.executeWithoutResult(s -> writer.publish(e));
        }

        TestEvent event(String type, String key) {
            return new TestEvent(d.tenant, type, key, Instant.parse("2026-09-27T08:00:00Z"), Map.of("ticketNo", key, "seq", 1));
        }

        int outboxCount(String where) {
            return d.f.jdbc.sql("SELECT COUNT(*) FROM plat_outbox WHERE " + where).query(Integer.class).single();
        }
    }

    private static Env env(TestDatabase db) {
        DirectoryEnv d = new DirectoryEnv(db);
        OutboxDao outbox = new OutboxDao(d.f.jdbc, db.dialect());
        WebhookDao webhooks = new WebhookDao(d.f.jdbc, db.dialect());
        OutboxWriter writer = new OutboxWriter(outbox, List.of(), d.f.ids, d.f.clock, new ai.neargo.opskit.platform.store.dao.WorkspaceDao(d.f.jdbc),
                new ai.neargo.opskit.platform.store.dao.MemberDao(d.f.jdbc, db.dialect()));
        OutboxRelay relay = new OutboxRelay(outbox, webhooks, d.apps, new WebhookDispatcher(d.f.clock),
                new Backoff(List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30))), d.f.clock, d.f.tx);
        return new Env(d, writer, relay, webhooks, outbox);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void envelopeCarriesTheHostRefsForWorkspaceAndMembers(TestDatabase db) throws Exception {
        Env e = env(db);
        ai.neargo.opskit.platform.api.directory.MemberView m = e.d.directory.provision(e.d.tenant,
                new ai.neargo.opskit.platform.api.directory.MemberUpsert("E107", "Ali", null, null), ai.neargo.opskit.platform.api.directory.DirectorySource.JIT);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("approvalNo", "AP1");
        data.put("actor", "member:" + m.memberId());
        data.put("decidedBy", "app:app_x");
        e.d.f.tx.executeWithoutResult(s -> e.writer.publish(new MemberEvent(e.d.tenant, "approval.completed", "AP1:1",
                Instant.parse("2026-09-27T08:00:00Z"), data)));
        String payload = e.d.f.jdbc.sql("SELECT payload FROM plat_outbox").query(String.class).single();
        tools.jackson.databind.JsonNode env = new tools.jackson.databind.ObjectMapper().readTree(payload);
        assertThat(env.get("workspaceExternalRef").asText()).isEqualTo("T0001");
        assertThat(env.at("/data/actor/ref").asText()).isEqualTo("member:" + m.memberId());
        assertThat(env.at("/data/actor/externalRef").asText()).isEqualTo("E107");
        assertThat(env.at("/data/actor/displayName").asText()).isEqualTo("Ali");
        assertThat(env.at("/data/decidedBy/ref").asText()).as("非成员只有 ref").isEqualTo("app:app_x");
        assertThat(env.at("/data/decidedBy").has("externalRef")).isFalse();
        assertThat(env.at("/data/approvalNo").asText()).as("其余字段原样").isEqualTo("AP1");
        assertThat(env.at("/data").has("absent")).as("没有的字段不凭空加").isFalse();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void deliversASignedEnvelopeOnceAndMarksItDone(TestDatabase db) throws Exception {
        Env e = env(db);
        AppSecrets app = e.d.apps.createApp(e.d.account, new CreateAppRequest("x", true, List.of(), null));
        try (Receiver r = new Receiver()) {
            e.webhooks.subscribe(e.d.account, null, app.app().appId(), "ticket.*", r.url());
            e.publish(e.event("ticket.created", "TK1:1"));
            e.publish(e.event("ticket.created", "TK1:1"));   // 写请求重试 → 同一事件再发一次

            assertThat(e.outboxCount("1 = 1")).as("同幂等键只入箱一次").isEqualTo(1);
            assertThat(e.relay.relayOnce()).isEqualTo(1);
            assertThat(e.relay.relayOnce()).as("已完成的不再投").isZero();

            assertThat(r.bodies).hasSize(1);
            assertThat(SignatureSpec.verify(app.signingSecret(), r.signatures.get(0), r.bodies.get(0), e.d.f.clock.millis())).isTrue();
            assertThat(SignatureSpec.verify("oks_wrong", r.signatures.get(0), r.bodies.get(0), e.d.f.clock.millis())).isFalse();
            assertThat(r.bodies.get(0)).contains("\"eventType\":\"ticket.created\"", "\"idemKey\":\"TK1:1\"", "\"ticketNo\":\"TK1:1\"",
                    "\"workspaceId\":\"" + e.d.workspace.value() + "\"");
            assertThat(e.outboxCount("done_at_ms IS NOT NULL")).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void failureBacksOffAndRetriesWhenDue(TestDatabase db) throws Exception {
        Env e = env(db);
        AppSecrets app = e.d.apps.createApp(e.d.account, new CreateAppRequest("x", true, List.of(), null));
        try (Receiver r = new Receiver()) {
            e.webhooks.subscribe(e.d.account, null, app.app().appId(), "*", r.url());
            e.publish(e.event("ticket.created", "TK1:1"));
            r.status.set(503);

            e.relay.relayOnce();
            assertThat(e.relay.relayOnce()).as("退避期内不重试").isZero();
            e.d.f.clock.advanceMillis(Duration.ofMinutes(1).toMillis());
            r.status.set(200);
            assertThat(e.relay.relayOnce()).isEqualTo(1);

            assertThat(r.bodies).hasSize(2);
            assertThat(e.outboxCount("done_at_ms IS NOT NULL AND attempts = 1")).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void retrySkipsSubscribersThatAlreadyGotIt(TestDatabase db) throws Exception {
        Env e = env(db);
        AppSecrets app = e.d.apps.createApp(e.d.account, new CreateAppRequest("x", true, List.of(), null));
        try (Receiver ok = new Receiver(); Receiver flaky = new Receiver()) {
            e.webhooks.subscribe(e.d.account, null, app.app().appId(), "*", ok.url());
            e.webhooks.subscribe(e.d.account, null, app.app().appId(), "*", flaky.url());
            e.publish(e.event("ticket.created", "TK1:1"));
            flaky.status.set(500);

            e.relay.relayOnce();
            e.d.f.clock.advanceMillis(Duration.ofMinutes(1).toMillis());
            flaky.status.set(200);
            e.relay.relayOnce();

            assertThat(ok.bodies).as("成功过的订阅方不再收到").hasSize(1);
            assertThat(flaky.bodies).hasSize(2);
        }
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void exhaustedDeliveryWaitsForManualRedelivery(TestDatabase db) throws Exception {
        Env e = env(db);
        AppSecrets app = e.d.apps.createApp(e.d.account, new CreateAppRequest("x", true, List.of(), null));
        try (Receiver r = new Receiver()) {
            e.webhooks.subscribe(e.d.account, null, app.app().appId(), "*", r.url());
            e.publish(e.event("ticket.created", "TK1:1"));
            r.status.set(500);
            for (int i = 0; i < 3; i++) {
                e.relay.relayOnce();
                e.d.f.clock.advanceMillis(Duration.ofHours(1).toMillis());
            }
            assertThat(e.outboxCount("failed = 1 AND attempts = 3")).isEqualTo(1);
            assertThat(e.relay.relayOnce()).as("失败的不再自动投").isZero();

            long id = e.d.f.jdbc.sql("SELECT id FROM plat_outbox").query(Long.class).single();
            r.status.set(200);
            e.d.f.tx.executeWithoutResult(s -> e.outbox.redeliver(e.d.account, id, e.d.f.clock.millis()));
            assertThat(e.relay.relayOnce()).isEqualTo(1);
            assertThat(e.outboxCount("done_at_ms IS NOT NULL")).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void subscriptionsFilterByWorkspaceAndType(TestDatabase db) throws Exception {
        Env e = env(db);
        AppSecrets app = e.d.apps.createApp(e.d.account, new CreateAppRequest("x", true, List.of(), null));
        String other = e.d.tenancy.createWorkspace(e.d.account, new CreateWorkspaceRequest("别的", "T0002", null, null)).workspaceId();
        try (Receiver mine = new Receiver(); Receiver theirs = new Receiver()) {
            e.webhooks.subscribe(e.d.account, e.d.workspace.value(), app.app().appId(), "ticket.*", mine.url());
            e.webhooks.subscribe(e.d.account, other, app.app().appId(), "*", theirs.url());
            e.publish(e.event("ticket.created", "TK1:1"));
            e.publish(e.event("instance.transitioned", "PI1:1"));
            e.relay.relayOnce();

            assertThat(mine.bodies).hasSize(1).allMatch(b -> b.contains("ticket.created"));
            assertThat(theirs.bodies).as("别的空间的订阅收不到本空间事件").isEmpty();
            assertThat(e.outboxCount("done_at_ms IS NOT NULL")).as("没有订阅方的事件也算完成").isEqualTo(2);
        }
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void publishingOutsideATransactionIsRefused(TestDatabase db) {
        Env e = env(db);
        assertThatThrownBy(() -> e.writer.publish(e.event("ticket.created", "TK1:1"))).isInstanceOf(IllegalStateException.class);
    }
}
