package ai.neargo.opskit.platform.core.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 平台层配置（21/03 §五）。 */
@ConfigurationProperties(prefix = "opskit")
public record PlatformProperties(Crypto crypto, Outbox outbox, Idempotency idempotency, Auth auth) {

    public PlatformProperties {
        crypto = crypto == null ? new Crypto(null) : crypto;
        outbox = outbox == null ? new Outbox(null, null) : outbox;
        idempotency = idempotency == null ? new Idempotency(null) : idempotency;
        auth = auth == null ? new Auth(null) : auth;
    }

    /** @param tokenCacheMaxTtl 内省结果缓存上限（还不超过令牌自身的 exp） */
    public record Auth(Duration tokenCacheMaxTtl) {
        public Auth {
            tokenCacheMaxTtl = tokenCacheMaxTtl == null ? Duration.ofMinutes(5) : tokenCacheMaxTtl;
        }
    }

    /** @param key 32 字节密钥的 Base64，只从环境变量 / 密钥管理注入，不进仓库；缺了拒绝启动 */
    public record Crypto(String key) {
    }

    public record Outbox(Duration relayInterval, List<Duration> backoff) {
        public Outbox {
            relayInterval = relayInterval == null ? Duration.ofSeconds(5) : relayInterval;
        }
    }

    public record Idempotency(Duration retention) {
        public Idempotency {
            retention = retention == null ? Duration.ofDays(7) : retention;
        }
    }
}
