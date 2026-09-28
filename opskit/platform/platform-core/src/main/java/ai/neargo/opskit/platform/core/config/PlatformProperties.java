package ai.neargo.opskit.platform.core.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 平台层配置（21/03 §五）。 */
@ConfigurationProperties(prefix = "opskit")
public record PlatformProperties(Crypto crypto, Outbox outbox, Idempotency idempotency, Auth auth) {

    public PlatformProperties {
        crypto = crypto == null ? new Crypto(null) : crypto;
        outbox = outbox == null ? new Outbox(null, null, null) : outbox;
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

    /**
     * @param retention 已送达事件（连同每次投递记录）保留多久，之后每日清掉；失败待人工与仍在重试的不清。
     *                  事件信封里可能有外部确认的一次性令牌明文，不宜无限期留存
     */
    public record Outbox(Duration relayInterval, List<Duration> backoff, Duration retention) {
        public Outbox {
            relayInterval = relayInterval == null ? Duration.ofSeconds(5) : relayInterval;
            retention = retention == null ? Duration.ofDays(30) : retention;
        }
    }

    public record Idempotency(Duration retention) {
        public Idempotency {
            retention = retention == null ? Duration.ofDays(7) : retention;
        }
    }
}
