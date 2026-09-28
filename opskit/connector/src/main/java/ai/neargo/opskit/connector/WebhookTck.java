package ai.neargo.opskit.connector;

import ai.neargo.opskit.platform.api.webhook.SignatureSpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 接收端一致性检查（TCK，21/07 X1）：接入方把自己的 webhook 端点与签名密钥交给它，跑一遍就知道
 * 验签 · 时间窗 · 重复投递 · 未知事件这几条有没有做对。只发一个无业务含义的事件类型 {@code opskit.tck.ping}，
 * 不会推进任何业务。
 *
 * <pre>
 * WebhookTck.run(URI.create("http://127.0.0.1:8082/internal/opskit/webhook"), System.getenv("OPSKIT_SIGNING_SECRET"))
 *         .forEach(System.out::println);
 * </pre>
 */
public final class WebhookTck {

    public record Check(String name, boolean passed, String detail) {
        @Override
        public String toString() {
            return (passed ? "✅ " : "❌ ") + name + (passed ? "" : "（" + detail + "）");
        }
    }

    private WebhookTck() {
    }

    public static List<Check> run(URI endpoint, String signingSecret) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String key = "tck:" + UUID.randomUUID();
        String body = "{\"eventId\":\"EV-TCK\",\"eventType\":\"opskit.tck.ping\",\"accountId\":\"ac_tck\",\"workspaceId\":\"ws_tck\","
                + "\"workspaceExternalRef\":\"TCK\",\"occurredAt\":" + System.currentTimeMillis() + ",\"idemKey\":\"" + key + "\",\"data\":{\"seq\":1}}";
        long now = System.currentTimeMillis();
        List<Check> out = new ArrayList<>();
        out.add(expect(http, endpoint, null, body, 401, "没有签名头 → 401"));
        out.add(expect(http, endpoint, SignatureSpec.sign("oks_not_the_secret_" + UUID.randomUUID(), now, body), body, 401, "签名密钥不对 → 401"));
        out.add(expect(http, endpoint, SignatureSpec.sign(signingSecret, now, body), body.replace("\"seq\":1", "\"seq\":2"), 401,
                "请求体被改过（签名对不上）→ 401"));
        out.add(expect(http, endpoint, SignatureSpec.sign(signingSecret, now - 10 * 60_000L, body), body, 401, "时间戳超出 ±5 分钟（重放）→ 401"));
        out.add(expect(http, endpoint, SignatureSpec.sign(signingSecret, now, body), body, 200, "签名正确的未知事件类型 → 2xx（确认收到，否则会被无限重投）"));
        out.add(expect(http, endpoint, SignatureSpec.sign(signingSecret, now, body), body, 200, "同一事件重投 → 2xx（按 idemKey 去重，不报错）"));
        return out;
    }

    private static Check expect(HttpClient http, URI endpoint, String signature, String body, int expected, String name) {
        HttpRequest.Builder b = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            b.header(SignatureSpec.HEADER, signature);
        }
        try {
            int status = http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            boolean ok = expected == 200 ? status / 100 == 2 : status == expected;
            return new Check(name, ok, "实际 " + status);
        } catch (IOException e) {
            return new Check(name, false, "请求失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Check(name, false, "被中断");
        }
    }
}
