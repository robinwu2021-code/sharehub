package ai.neargo.powerbank.auth.store;

import ai.neargo.powerbank.auth.TokenStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地缓存 TokenStore（{@code token-store=ehcache} 单节点带 TTL 模式）。
 * 自包含 TTL + 惰性过期，无外部依赖；生产可平替真 Ehcache/JCache（同 SPI 语义）。
 */
public class LocalCacheTokenStore implements TokenStore {

    private record Entry(SessionData data, Instant expireAt) {
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Duration ttl;

    public LocalCacheTokenStore(Duration ttl) {
        this.ttl = ttl;
    }

    @Override
    public String issue(SessionData data) {
        String token = TokenStore.newToken(data.user().realm());
        cache.put(token, new Entry(data, Instant.now().plus(ttl)));
        return token;
    }

    @Override
    public Optional<SessionData> get(String token) {
        if (token == null) {
            return Optional.empty();
        }
        Entry e = cache.get(token);
        if (e == null) {
            return Optional.empty();
        }
        if (e.expireAt().isBefore(Instant.now())) {   // 惰性过期
            cache.remove(token);
            return Optional.empty();
        }
        return Optional.of(e.data());
    }

    @Override
    public void refresh(String token, SessionData data) {
        if (token != null && cache.containsKey(token)) {
            cache.put(token, new Entry(data, Instant.now().plus(ttl)));
        }
    }

    @Override
    public void revoke(String token) {
        if (token != null) {
            cache.remove(token);
        }
    }
}
