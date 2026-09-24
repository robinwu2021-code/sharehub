package ai.neargo.sharehub.config;

import ai.neargo.sharehub.auth.TokenStore;
import ai.neargo.sharehub.auth.store.LocalCacheTokenStore;
import ai.neargo.sharehub.auth.store.MemoryTokenStore;
import ai.neargo.sharehub.auth.store.CachedTokenStore;
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

    /**
     * 纯内存 —— **只适合单测与本地起服**，重启即掉线、多副本各记各的。
     *
     * <p>2026-09-24：**不再是缺省**。缺省改为 {@code mysql+ehcache}（见下），
     * 因为「默认给一个重启就掉全部登录态的实现」是个会在生产上才暴露的默认值。
     */
    @Bean
    @ConditionalOnProperty(name = KEY, havingValue = "memory")
    public TokenStore memoryTokenStore(@Value("${sharehub.auth.token-ttl:2h}") Duration ttl) {
        return new MemoryTokenStore(ttl);
    }

    @Bean
    @ConditionalOnProperty(name = KEY, havingValue = "ehcache")
    public TokenStore localCacheTokenStore(@Value("${sharehub.auth.token-ttl:2h}") Duration ttl) {
        return new LocalCacheTokenStore(ttl);
    }

    /**
     * 纯 Redis。**刻意不套 {@link CachedTokenStore}**：Redis 本身就是远程缓存，
     * 再叠本地缓存会把「吊销多久生效」从一次网络往返延长到 cache-ttl，
     * 而那点往返正是多副本下能即时吊销的代价。真要叠，把 cache-ttl 压到秒级并想清楚这笔账。
     */
    @Bean
    @ConditionalOnProperty(name = KEY, havingValue = "redis")
    public TokenStore redisTokenStore(StringRedisTemplate redis,
                                      @Value("${sharehub.auth.token-ttl:2h}") Duration ttl) {
        return new RedisTokenStore(redis, authOm, ttl);
    }

    /** 纯 MySQL：每次鉴权一次查库。正确但热路径上每个请求都要走一次 IO。 */
    @Bean
    @ConditionalOnProperty(name = KEY, havingValue = "mysql")
    public TokenStore mysqlTokenStore(SysTokenMapper mapper,
                                      @Value("${sharehub.auth.token-ttl:2h}") Duration ttl) {
        return new MysqlTokenStore(mapper, authOm, ttl);
    }

    /**
     * **缺省：MySQL 持久 + 本地缓存热层**（{@code token-store=mysql+ehcache} 或不配）。
     *
     * <p>选它当缺省的理由：会话要活过重启（纯 memory 不行），
     * 又不该让每个请求都查一次库（纯 mysql 会）。
     *
     * <p><b>换 Redis 时这里只改一行</b>：{@code new CachedTokenStore(redisStore, cacheTtl)} ——
     * 缓存层与持久层在 {@link CachedTokenStore} 里是正交的，不会因为换存储而重写。
     * 这正是把它做成装饰器而不是第五个实现的原因。
     *
     * <p>{@code cache-ttl} 与 {@code token-ttl} 是两回事，别配成一样：
     * 前者是「最多容忍多久看不见别处的吊销」（默认 30s），
     * 后者是「会话本身多久过期」（默认 2h）。多副本时前者就是吊销延迟的上界。
     */
    @Bean
    @ConditionalOnProperty(name = KEY, havingValue = "mysql+ehcache", matchIfMissing = true)
    public TokenStore cachedMysqlTokenStore(
            SysTokenMapper mapper,
            @Value("${sharehub.auth.token-ttl:2h}") Duration ttl,
            @Value("${sharehub.auth.token-cache-ttl:30s}") Duration cacheTtl) {
        return new CachedTokenStore(new MysqlTokenStore(mapper, authOm, ttl), cacheTtl);
    }
}
