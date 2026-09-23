package ai.neargo.sharehub.auth.store;

import ai.neargo.sharehub.auth.TokenStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存 TokenStore（默认档；本机 / 单测 / 单实例）。进程内、重启即失效。
 *
 * <p><b>2026-09-23 安全止血</b>（TDD-auth-security-hotfix）：此前**没有 TTL**，令牌到进程重启前
 * 永不失效 —— 而它又是缺省实现（`token-store` 开关当时因键名写错而失效，配 redis 也回落到这里），
 * 等于生产上令牌永不过期。现在与 {@link LocalCacheTokenStore} 同语义：滑动续期 + 惰性过期。
 *
 * <p>滑动续期：每次 {@code refresh} 重置到期时间，活跃会话不会用着用着被踢。
 * 多实例仍不共享 —— 生产用 {@code sharehub.auth.token-store=redis}。
 */
public class MemoryTokenStore implements TokenStore {

    private record Entry(SessionData data, Instant expireAt) {
    }

    private final ConcurrentHashMap<String, Entry> sessions = new ConcurrentHashMap<>();
    private final Duration ttl;

    public MemoryTokenStore(Duration ttl) {
        this.ttl = ttl;
    }

    @Override
    public String issue(SessionData data) {
        String token = TokenStore.newToken(data.user().realm());
        sessions.put(token, new Entry(data, Instant.now().plus(ttl)));
        return token;
    }

    @Override
    public Optional<SessionData> get(String token) {
        if (token == null) {
            return Optional.empty();
        }
        Entry e = sessions.get(token);
        if (e == null) {
            return Optional.empty();
        }
        if (e.expireAt().isBefore(Instant.now())) {   // 惰性过期
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(e.data());
    }

    @Override
    public void refresh(String token, SessionData data) {
        if (token != null && sessions.containsKey(token)) {
            sessions.put(token, new Entry(data, Instant.now().plus(ttl)));
        }
    }

    @Override
    public void revoke(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }
}
