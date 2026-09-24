package ai.neargo.sharehub.auth;

import ai.neargo.sharehub.auth.store.CachedTokenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 令牌热层装饰器（{@code token-store=mysql+ehcache} 的那一半）。
 *
 * <p>不起 Spring 上下文，用一个**会计数的假下游**代替 MySQL —— 本类要验的是
 * 「何时问下游、何时不问」，那与下游是 MySQL 还是 Redis 无关。
 * 这也正是把缓存做成装饰器的好处：它可以脱离存储单独测。
 */
class CachedTokenStoreTest {

    /** 记账用的假下游：谁调了几次、现在还剩什么。 */
    /** 非 final：第三条测试要用匿名子类在 revoke 时回探本地缓存状态。 */
    private static class CountingStore implements TokenStore {
        final Map<String, SessionData> data = new ConcurrentHashMap<>();
        final AtomicInteger gets = new AtomicInteger();
        final AtomicInteger revokes = new AtomicInteger();
        int seq;

        @Override
        public String issue(SessionData d) {
            String t = "tk_" + (++seq);
            data.put(t, d);
            return t;
        }

        @Override
        public Optional<SessionData> get(String token) {
            gets.incrementAndGet();
            return Optional.ofNullable(data.get(token));
        }

        @Override
        public void refresh(String token, SessionData d) {
            data.put(token, d);
        }

        @Override
        public void revoke(String token) {
            revokes.incrementAndGet();
            data.remove(token);
        }
    }

    private static TokenStore.SessionData session() {
        return new TokenStore.SessionData(
                new LoginUser(Realm.STAFF, "U1", "张三", "ADMIN", List.of("*"), "MAIN", null, null),
                List.of("R1"), 1L);
    }

    @Test
    @DisplayName("命中热层就不问下游 —— 这是套这一层的全部意义")
    void hitDoesNotTouchDelegate() {
        CountingStore down = new CountingStore();
        CachedTokenStore store = new CachedTokenStore(down, Duration.ofSeconds(30));

        String t = store.issue(session());
        int after签发 = down.gets.get();
        for (int i = 0; i < 5; i++) assertThat(store.get(t)).isPresent();

        assertThat(down.gets.get())
                .as("签发时已入热层，之后 5 次读一次库都不该查 —— 否则缓存等于没加")
                .isEqualTo(after签发);
    }

    @Test
    @DisplayName("吊销必须立刻生效，不能等热层 TTL 到期")
    void revokeIsImmediate() {
        CountingStore down = new CountingStore();
        CachedTokenStore store = new CachedTokenStore(down, Duration.ofHours(1)); // 故意给很长的热层 TTL

        String t = store.issue(session());
        assertThat(store.get(t)).isPresent();

        store.revoke(t);

        assertThat(store.get(t))
                .as("热层 TTL 还有一小时，但吊销必须立刻可见 —— 否则「退出登录」只是个动画")
                .isEmpty();
        assertThat(down.revokes.get()).as("下游也要真删").isEqualTo(1);
    }

    @Test
    @DisplayName("吊销顺序：先清本地再删下游")
    void revokeClearsLocalBeforeDelegate() {
        CountingStore down = new CountingStore();
        // 下游删除时回头读一次 —— 模拟「两步之间恰好来了一个请求」
        CachedTokenStore[] holder = new CachedTokenStore[1];
        CountingStore probing = new CountingStore() {
            @Override
            public void revoke(String token) {
                // 此刻本地应当已经清掉了；若顺序反了，这里会读到缓存里的旧值并把它留在热层
                assertThat(holder[0].cachedCount())
                        .as("删下游之前本地必须已清 —— 顺序反了的话，两步之间的一次读会把"
                                + "刚吊销的会话又从下游捞回缓存")
                        .isZero();
                super.revoke(token);
            }
        };
        CachedTokenStore store = new CachedTokenStore(probing, Duration.ofHours(1));
        holder[0] = store;

        String t = store.issue(session());
        store.get(t);
        store.revoke(t);

        assertThat(store.get(t)).isEmpty();
    }

    @Test
    @DisplayName("热层过期只是「该去问下游了」，不等于会话失效")
    void localExpiryFallsBackToDelegate() throws InterruptedException {
        CountingStore down = new CountingStore();
        CachedTokenStore store = new CachedTokenStore(down, Duration.ofMillis(50));

        String t = store.issue(session());
        Thread.sleep(80);

        assertThat(store.get(t))
                .as("本地 50ms 过期，但下游那份会话还在 —— 必须回源拿到，而不是判成未登录")
                .isPresent();
        assertThat(down.gets.get()).as("这次应当真的问了下游").isPositive();
    }

    @Test
    @DisplayName("refresh 后新权限立刻可见，不等热层 TTL")
    void refreshInvalidatesHotLayer() {
        CountingStore down = new CountingStore();
        CachedTokenStore store = new CachedTokenStore(down, Duration.ofHours(1));

        String t = store.issue(session());
        store.get(t);

        TokenStore.SessionData updated = new TokenStore.SessionData(
                new LoginUser(Realm.STAFF, "U1", "张三", "VIEWER", List.of("dashboard:overview:read"), "MAIN", null, null),
                List.of("R2"), 2L);
        store.refresh(t, updated);

        assertThat(store.get(t).orElseThrow().permStamp())
                .as("改了权限还让旧的挂一小时，等于权限变更不生效")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("cache-ttl 配 0 = 直通，每次都问下游")
    void zeroTtlMeansPassThrough() {
        CountingStore down = new CountingStore();
        CachedTokenStore store = new CachedTokenStore(down, Duration.ZERO);

        String t = store.issue(session());
        store.get(t);
        store.get(t);

        assertThat(down.gets.get())
                .as("配 0 应当等于不缓存 —— 多副本要即时全局吊销时就这么配")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("查不到的令牌不写负缓存")
    void missesAreNotCached() {
        CountingStore down = new CountingStore();
        CachedTokenStore store = new CachedTokenStore(down, Duration.ofHours(1));

        store.get("forged");
        store.get("forged");

        assertThat(down.gets.get())
                .as("令牌是高基数且大多是伪造/过期的，缓存它们只会把真会话挤出去")
                .isEqualTo(2);
        assertThat(store.cachedCount()).isZero();
    }
}
