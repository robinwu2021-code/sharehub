package ai.neargo.powerbank.auth.store;

import ai.neargo.powerbank.auth.TokenStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis TokenStore（{@code token-store=redis}，**多实例生产推荐**）：
 * key {@code pb:auth:token:{token}} = JSON(SessionData)，原生 EXPIRE 做 TTL。
 * 会话跨实例共享、原生过期、可 DEL 踢下线。序列化用 Jackson（SessionData 为 record）。
 */
public class RedisTokenStore implements TokenStore {

    private static final String PREFIX = "pb:auth:token:";

    private final StringRedisTemplate redis;
    private final ObjectMapper om;
    private final Duration ttl;

    public RedisTokenStore(StringRedisTemplate redis, ObjectMapper om, Duration ttl) {
        this.redis = redis;
        this.om = om;
        this.ttl = ttl;
    }

    @Override
    public String issue(SessionData data) {
        String token = TokenStore.newToken(data.user().realm());
        redis.opsForValue().set(PREFIX + token, toJson(data), ttl);
        return token;
    }

    @Override
    public Optional<SessionData> get(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String v = redis.opsForValue().get(PREFIX + token);
        return v == null ? Optional.empty() : Optional.of(fromJson(v));
    }

    @Override
    public void refresh(String token, SessionData data) {
        if (token != null) {
            redis.opsForValue().set(PREFIX + token, toJson(data), ttl);   // 覆盖 + 续 TTL
        }
    }

    @Override
    public void revoke(String token) {
        if (token != null) {
            redis.delete(PREFIX + token);
        }
    }

    private String toJson(SessionData d) {
        try {
            return om.writeValueAsString(d);
        } catch (Exception e) {
            throw new IllegalStateException("会话序列化失败", e);
        }
    }

    private SessionData fromJson(String v) {
        try {
            return om.readValue(v, SessionData.class);
        } catch (Exception e) {
            throw new IllegalStateException("会话反序列化失败", e);
        }
    }
}
