package ai.neargo.sharehub.auth.store;

import ai.neargo.sharehub.auth.TokenStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 给任意 {@link TokenStore} 套一层本地缓存 —— **热层在内存，权威在下游**。
 *
 * <h2>为什么是装饰器而不是第五个实现</h2>
 * 「数据库 + 本地缓存」如果写成一个新类，将来换 Redis 时这段缓存逻辑要么重写一遍、
 * 要么被丢掉。装饰器让缓存与持久化正交：{@code new CachedTokenStore(mysql)} 与
 * {@code new CachedTokenStore(redis)} 是同一段代码。
 *
 * <p>（Redis 自己就是远程缓存，套本地缓存是为了省往返 —— 但那时缓存 TTL
 * 要压得更短，因为多副本下本地缓存看不见别的副本的吊销。见下。）
 *
 * <h2>吊销是这类设计唯一真正的难点</h2>
 * 缓存让读变快，也让**吊销变慢**：下游删了，本副本的缓存还记得。
 * 三条处理：
 * <ol>
 *   <li>{@link #revoke} 先删本地再删下游 —— 顺序反了的话，两步之间的读会把刚吊销的
 *       会话又从下游捞回缓存（下游那次删还没发生）；</li>
 *   <li>本地缓存 TTL **独立且远短于**会话 TTL（默认 30s）。它不是"会话有效期"，
 *       是"最多容忍多久看不见别处的吊销"；</li>
 *   <li>多副本部署时，本副本的吊销传不到别的副本 —— 那是 {@code cache-ttl} 的上界所在。
 *       要做到即时全局吊销，得上 Redis 的发布订阅或把 cache-ttl 设为 0（等于不缓存）。
 *       <b>单体阶段不需要，但这个取舍必须写在这里，而不是等出事时再去猜。</b></li>
 * </ol>
 *
 * <h2>不缓存「查不到」</h2>
 * 未命中不写负缓存：令牌是高基数且大多是伪造/过期的，缓存它们只会挤掉真会话。
 */
public class CachedTokenStore implements TokenStore {

    private record Entry(SessionData data, Instant expireAt) {
    }

    private final TokenStore delegate;
    private final Duration cacheTtl;
    private final Map<String, Entry> hot = new ConcurrentHashMap<>();

    public CachedTokenStore(TokenStore delegate, Duration cacheTtl) {
        this.delegate = delegate;
        this.cacheTtl = cacheTtl;
    }

    @Override
    public String issue(SessionData data) {
        String token = delegate.issue(data);
        put(token, data);
        return token;
    }

    @Override
    public Optional<SessionData> get(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Entry e = hot.get(token);
        if (e != null) {
            if (Instant.now().isBefore(e.expireAt())) return Optional.of(e.data());
            hot.remove(token);   // 本地过期只是"该去问下游了"，不代表会话失效
        }
        Optional<SessionData> fromDelegate = delegate.get(token);
        fromDelegate.ifPresent(d -> put(token, d));
        return fromDelegate;
    }

    @Override
    public void refresh(String token, SessionData data) {
        delegate.refresh(token, data);
        put(token, data);   // 权限变更后必须立刻可见，不能等本地 TTL 到期
    }

    @Override
    public void revoke(String token) {
        // **先本地后下游**：反过来的话，两步之间的一次读会把已吊销的会话重新装进缓存
        hot.remove(token);
        delegate.revoke(token);
    }

    private void put(String token, SessionData data) {
        if (cacheTtl.isZero() || cacheTtl.isNegative()) return;   // 配 0 = 直通，不缓存
        hot.put(token, new Entry(data, Instant.now().plus(cacheTtl)));
    }

    /** 仅供测试：当前热层条目数。 */
    public int cachedCount() {
        return hot.size();
    }
}
