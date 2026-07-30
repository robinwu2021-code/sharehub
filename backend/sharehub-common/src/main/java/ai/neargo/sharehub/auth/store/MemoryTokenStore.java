package ai.neargo.powerbank.auth.store;

import ai.neargo.powerbank.auth.TokenStore;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存 TokenStore（默认；本机/单测/单实例）。无 TTL、进程内、重启即失效。
 * 生产多实例换 {@code RedisTokenStore}（{@code powerbank.auth.token-store=redis}）。
 */
public class MemoryTokenStore implements TokenStore {

    private final ConcurrentHashMap<String, SessionData> sessions = new ConcurrentHashMap<>();

    @Override
    public String issue(SessionData data) {
        String token = TokenStore.newToken(data.user().realm());
        sessions.put(token, data);
        return token;
    }

    @Override
    public Optional<SessionData> get(String token) {
        return Optional.ofNullable(token == null ? null : sessions.get(token));
    }

    @Override
    public void refresh(String token, SessionData data) {
        if (token != null) {
            sessions.put(token, data);
        }
    }

    @Override
    public void revoke(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }
}
