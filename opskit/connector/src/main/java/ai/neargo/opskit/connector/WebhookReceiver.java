package ai.neargo.opskit.connector;

import ai.neargo.opskit.platform.api.webhook.SignatureSpec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * webhook 接收器（21/07 X1）。框架无关：你的 HTTP 端点把签名头与<b>原始请求体字符串</b>交给 {@link #handle}，
 * 按返回的状态码应答即可。
 *
 * <pre>
 * 签名不对 / 过期（±5 分钟）  → 401，不碰业务
 * 体不是 JSON                → 400
 * 重复事件（台账已记）        → 200，不再调处理器
 * 没有处理器的事件类型        → 200（确认收到；OpsKit 不再重投）
 * 处理器抛异常                → 撤销台账 → 500（OpsKit 退避重投）
 * </pre>
 * 注意：验签必须用收到的原始字节，不能先反序列化再序列化（键顺序、空白一变签名就对不上）。
 */
public final class WebhookReceiver {

    /** 业务处理。抛异常 = 这次没处理好，OpsKit 会重投。 */
    @FunctionalInterface
    public interface Handler {
        void handle(Envelope event) throws Exception;
    }

    public record Response(int status, String message) {
    }

    private record Route(String pattern, Handler handler) {
    }

    private final String signingSecret;
    private final EventLedger ledger;
    private final Clock clock;
    private final List<Route> routes = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper();

    /** @param signingSecret 应用的签名密钥 {@code oks_…}（与凭证同时签发 / 声明） */
    public WebhookReceiver(String signingSecret, EventLedger ledger, Clock clock) {
        this.signingSecret = Objects.requireNonNull(signingSecret, "signingSecret");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @param eventTypePattern 精确类型、前缀通配（{@code ticket.*}）或 {@code *}；按注册顺序取第一个匹配 */
    public WebhookReceiver on(String eventTypePattern, Handler handler) {
        routes.add(new Route(eventTypePattern, handler));
        return this;
    }

    public Response handle(String signatureHeader, String rawBody) {
        if (rawBody == null || !SignatureSpec.verify(signingSecret, signatureHeader, rawBody, clock.millis())) {
            return new Response(401, "bad signature");
        }
        Envelope e;
        try {
            JsonNode n = json.readTree(rawBody);
            e = Envelope.of(n);
        } catch (RuntimeException ex) {
            return new Response(400, "malformed envelope");
        }
        Handler h = routes.stream().filter(r -> matches(r.pattern(), e.eventType())).map(Route::handler).findFirst().orElse(null);
        if (h == null) {
            return new Response(200, "ignored");
        }
        if (!ledger.firstTime(e.eventType(), e.idemKey())) {
            return new Response(200, "duplicate");
        }
        try {
            h.handle(e);
            return new Response(200, "ok");
        } catch (Exception ex) {
            ledger.forget(e.eventType(), e.idemKey());
            return new Response(500, "handler failed: " + ex.getMessage());
        }
    }

    static boolean matches(String pattern, String type) {
        if ("*".equals(pattern)) {
            return true;
        }
        if (pattern.endsWith(".*")) {
            return type.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(type);
    }
}
