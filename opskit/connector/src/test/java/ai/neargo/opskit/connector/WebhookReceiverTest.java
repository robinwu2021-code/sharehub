package ai.neargo.opskit.connector;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.opskit.platform.api.webhook.SignatureSpec;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 21/07 X1：接收器的应答契约，以及 TCK 能分辨合格与不合格的接收端（后者是 TCK 自己的负对照）。 */
class WebhookReceiverTest {

    static final String SECRET = "oks_" + "k".repeat(40);
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);

    static String event(String type, String key) {
        return "{\"eventId\":\"EV1\",\"eventType\":\"" + type + "\",\"accountId\":\"ac\",\"workspaceId\":\"ws\",\"workspaceExternalRef\":\"MAIN\","
                + "\"occurredAt\":1,\"idemKey\":\"" + key + "\",\"data\":{\"seq\":3,\"subject\":{\"type\":\"withdrawal\",\"ref\":\"WD1\"},"
                + "\"decidedBy\":{\"ref\":\"member:mb_1\",\"externalRef\":\"U2\",\"displayName\":\"运营\"}}}";
    }

    static String sign(String body) {
        return SignatureSpec.sign(SECRET, CLOCK.millis(), body);
    }

    @Test
    void verifiesDeduplicatesAndLetsFailuresBeRedelivered() {
        List<Envelope> handled = new CopyOnWriteArrayList<>();
        AtomicInteger failNext = new AtomicInteger(1);
        WebhookReceiver r = new WebhookReceiver(SECRET, EventLedger.inMemory(), CLOCK).on("approval.completed", e -> {
            if (failNext.getAndDecrement() > 0) {
                throw new IllegalStateException("db down");
            }
            handled.add(e);
        });
        String body = event("approval.completed", "AP1:1");

        assertThat(r.handle(null, body).status()).isEqualTo(401);
        assertThat(r.handle(SignatureSpec.sign("oks_other", CLOCK.millis(), body), body).status()).isEqualTo(401);
        assertThat(r.handle(sign(body), body.replace("WD1", "WD2")).status()).as("体被改").isEqualTo(401);

        assertThat(r.handle(sign(body), body).status()).as("处理失败 → 500，OpsKit 会重投").isEqualTo(500);
        assertThat(r.handle(sign(body), body).status()).as("重投成功").isEqualTo(200);
        assertThat(r.handle(sign(body), body).message()).as("再重投只是重复").isEqualTo("duplicate");
        assertThat(handled).hasSize(1);
        Envelope e = handled.get(0);
        assertThat(e.subjectRef()).isEqualTo("WD1");
        assertThat(e.externalRef("decidedBy")).isEqualTo("U2");
        assertThat(e.seq()).isEqualTo(3);
        assertThat(e.workspaceExternalRef()).isEqualTo("MAIN");

        String unknown = event("sla.warning", "C1:1");
        assertThat(r.handle(sign(unknown), unknown).message()).as("没有处理器也确认收到").isEqualTo("ignored");
        assertThat(r.handle(sign("not json"), "not json").status()).isEqualTo(400);
    }

    @Test
    void tckPassesACorrectReceiverAndFlagsASloppyOne() throws Exception {
        WebhookReceiver good = new WebhookReceiver(SECRET, EventLedger.inMemory(), Clock.systemUTC()).on("*", e -> { });
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/good", ex -> {
            WebhookReceiver.Response res = good.handle(ex.getRequestHeaders().getFirst(SignatureSpec.HEADER),
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(res.status(), -1);
            ex.close();
        });
        // 不验签、一律 200 的接收端：TCK 必须把它揪出来
        server.createContext("/sloppy", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            List<WebhookTck.Check> ok = WebhookTck.run(URI.create(base + "/good"), SECRET);
            assertThat(ok).hasSize(6).allMatch(WebhookTck.Check::passed);
            List<WebhookTck.Check> bad = WebhookTck.run(URI.create(base + "/sloppy"), SECRET);
            assertThat(bad.stream().filter(c -> !c.passed()).map(WebhookTck.Check::name).toList())
                    .as("四条安全检查都不过").hasSize(4);
        } finally {
            server.stop(0);
        }
    }
}
