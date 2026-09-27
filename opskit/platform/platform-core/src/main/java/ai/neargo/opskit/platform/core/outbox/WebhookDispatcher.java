package ai.neargo.opskit.platform.core.outbox;

import ai.neargo.opskit.platform.api.webhook.SignatureSpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;

/**
 * 一次 HTTP 投递。这是服务里仅有的两处出站 HTTP 之一（另一处是令牌校验，21/02 §1.1 R1），由架构测试 A8 守住。
 */
public class WebhookDispatcher {

    public record Result(Integer httpStatus, int latencyMs, String error) {
        public boolean ok() {
            return httpStatus != null && httpStatus >= 200 && httpStatus < 300;
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Clock clock;

    public WebhookDispatcher(Clock clock) {
        this.clock = clock;
    }

    public Result deliver(String url, String body, String secret, String eventType, String idemKey) {
        long start = System.nanoTime();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header(SignatureSpec.HEADER, SignatureSpec.sign(secret, clock.millis(), body))
                    .header("X-OpsKit-Event", eventType)
                    .header("X-OpsKit-Idempotency-Key", idemKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            return new Result(resp.statusCode(), elapsed(start), null);
        } catch (IOException | IllegalArgumentException e) {
            return new Result(null, elapsed(start), truncate(e.getClass().getSimpleName() + ": " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(null, elapsed(start), "interrupted");
        }
    }

    private static int elapsed(long startNanos) {
        return (int) Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
