package ai.neargo.opskit.platform.core.auth;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.json.JsonMapper;

/**
 * 不透明令牌的内省（RFC 7662 形状：POST form {@code token=…}，可选 Basic 认证，返回 {@code {"active":true, …}}）。
 *
 * <p>这是服务里仅有的两处出站 HTTP 之一（另一处是 webhook 投递），是「服务不同步调用接入方」的唯一例外：
 * 它调的是认证基础设施，不是业务数据（21/02 §8.2）。
 *
 * <p>结果按「令牌剩余有效期」与上限取小缓存：否则每个请求都要打宿主一次。inactive 不缓存 —— 刚登录的令牌要立刻可用。
 * 缓存键是令牌的 SHA-256，内存里不留令牌原文。
 */
final class Introspector {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_ENTRIES = 50_000;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private record Cached(Map<String, Object> claims, long expiresAtMs) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration maxTtl;

    Introspector(Clock clock, Duration maxTtl) {
        this.clock = clock;
        this.maxTtl = maxTtl;
    }

    Map<String, Object> introspect(String token, String url, String clientId, String clientSecret) {
        String key = sha256(url + "|" + token);
        long now = clock.millis();
        Cached hit = cache.get(key);
        if (hit != null && hit.expiresAtMs() > now) {
            return hit.claims();
        }
        Map<String, Object> claims = call(token, url, clientId, clientSecret);
        if (!Boolean.TRUE.equals(claims.get("active"))) {
            cache.remove(key);
            throw new JwtVerifier.BadToken(null);
        }
        long until = now + maxTtl.toMillis();
        if (claims.get("exp") instanceof Number exp) {
            until = Math.min(until, exp.longValue() * 1000);
        }
        if (cache.size() >= MAX_ENTRIES) {
            cache.entrySet().removeIf(e -> e.getValue().expiresAtMs() <= now);
            if (cache.size() >= MAX_ENTRIES) {
                cache.clear();
            }
        }
        cache.put(key, new Cached(Map.copyOf(claims), until));
        return claims;
    }

    void clear() {
        cache.clear();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String token, String url, String clientId, String clientSecret) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)));
            if (clientId != null) {
                b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8)));
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new JwtVerifier.BadToken(new IOException("introspection HTTP " + resp.statusCode()));
            }
            return JSON.readValue(resp.body(), Map.class);
        } catch (IOException e) {
            throw new JwtVerifier.BadToken(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JwtVerifier.BadToken(e);
        } catch (RuntimeException e) {
            if (e instanceof JwtVerifier.BadToken b) {
                throw b;
            }
            throw new JwtVerifier.BadToken(e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
