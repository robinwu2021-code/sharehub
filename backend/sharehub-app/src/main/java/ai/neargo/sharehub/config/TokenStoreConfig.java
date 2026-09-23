package ai.neargo.sharehub.config;

import ai.neargo.sharehub.auth.TokenStore;
import ai.neargo.sharehub.auth.store.LocalCacheTokenStore;
import ai.neargo.sharehub.auth.store.MemoryTokenStore;
import ai.neargo.sharehub.auth.store.MysqlTokenStore;
import ai.neargo.sharehub.auth.store.RedisTokenStore;
import ai.neargo.sharehub.auth.store.SysToken.SysTokenMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 可切换 TokenStore 装配（{@code sharehub.auth.token-store}=memory|ehcache|redis|mysql，缺省 memory）。
 * 认证过滤器/登录只依赖 {@link TokenStore} 接口——切换后端零改代码。见 docs/technical/权限体系设计.md §7。
 */
@Configuration
public class TokenStoreConfig {

    /**
     * 开关键名。**2026-09-23 修正**：此前写的是 {@code powerbank.auth.token-store}，
     * 而 application.yml 配的是 {@code sharehub.auth.token-store} —— 键名对不上，
     * 加上 memory 分支 {@code matchIfMissing = true}，导致**配 redis/mysql 也永远拿到
     * 没有过期的内存实现**（这正是「令牌不过期」的根因）。
     */
    private static final String KEY = "sharehub.auth.token-store";

    /**
     * 会话序列化专用 ObjectMapper（自建，不依赖 web 上下文 bean；Jackson 原生支持 record/enum）。
     * 关闭 FAIL_ON_UNKNOWN_PROPERTIES：LoginUser.isConsumer()/DataScope.isEmpty() 会被序列化为额外
     * "consumer"/"empty" 字段，反序列化到 record 时需忽略这些非构造参数字段。
     */
    private final ObjectMapper authOm = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Bean
    @ConditionalOnProperty(name = KEY, havingValue = "memory", matchIfMissing = true)
    public TokenStore memoryTokenStore(@Value("${sharehub.auth.token-ttl:2h}") Duration ttl) {
        return new MemoryTokenStore(ttl);
    }

    @Bean
    @ConditionalOnProperty(name = KEY, havingValue = "ehcache")
    public TokenStore localCacheTokenStore(@Value("${sharehub.auth.token-ttl:2h}") Duration ttl) {
        return new LocalCacheTokenStore(ttl);
    }

    @Bean
    @ConditionalOnProperty(name = KEY, havingValue = "redis")
    public TokenStore redisTokenStore(StringRedisTemplate redis,
                                      @Value("${sharehub.auth.token-ttl:2h}") Duration ttl) {
        return new RedisTokenStore(redis, authOm, ttl);
    }

    @Bean
    @ConditionalOnProperty(name = KEY, havingValue = "mysql")
    public TokenStore mysqlTokenStore(SysTokenMapper mapper,
                                      @Value("${sharehub.auth.token-ttl:2h}") Duration ttl) {
        return new MysqlTokenStore(mapper, authOm, ttl);
    }
}
