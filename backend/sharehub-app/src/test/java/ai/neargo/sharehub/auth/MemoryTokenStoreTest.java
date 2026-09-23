package ai.neargo.sharehub.auth;

import ai.neargo.sharehub.auth.store.MemoryTokenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MemoryTokenStore} 的 TTL 行为（TDD-auth-security-hotfix T6）。
 *
 * <p>此前它没有任何过期，而它又是缺省实现 —— 令牌到进程重启前永不失效。
 */
class MemoryTokenStoreTest {

    private static TokenStore.SessionData session() {
        LoginUser user = new LoginUser(Realm.STAFF, "u1", "u1", "VIEWER",
                List.of(), "MAIN", "", null);
        return new TokenStore.SessionData(user, List.of("VIEWER"), 1L);
    }

    @Test
    @DisplayName("未过期时可取回")
    void getBeforeExpiry() {
        MemoryTokenStore store = new MemoryTokenStore(Duration.ofMinutes(5));
        String token = store.issue(session());
        assertThat(store.get(token)).isPresent();
    }

    @Test
    @DisplayName("到期后取不回（惰性过期）")
    void expiresAfterTtl() throws Exception {
        MemoryTokenStore store = new MemoryTokenStore(Duration.ofMillis(30));
        String token = store.issue(session());
        Thread.sleep(60);
        assertThat(store.get(token)).as("过期令牌必须取不回").isEmpty();
    }

    @Test
    @DisplayName("refresh 滑动续期：活跃会话不被踢")
    void refreshSlidesExpiry() throws Exception {
        MemoryTokenStore store = new MemoryTokenStore(Duration.ofMillis(120));
        String token = store.issue(session());
        Thread.sleep(80);
        store.refresh(token, session());       // 续期
        Thread.sleep(80);                      // 总计 160ms > 120ms，但续期后未到期
        assertThat(store.get(token)).isPresent();
    }

    @Test
    @DisplayName("revoke 立即失效（登出）")
    void revokeWorks() {
        MemoryTokenStore store = new MemoryTokenStore(Duration.ofMinutes(5));
        String token = store.issue(session());
        store.revoke(token);
        assertThat(store.get(token)).isEmpty();
    }

    @Test
    @DisplayName("refresh 不会复活已吊销的令牌")
    void refreshDoesNotResurrect() {
        MemoryTokenStore store = new MemoryTokenStore(Duration.ofMinutes(5));
        String token = store.issue(session());
        store.revoke(token);
        store.refresh(token, session());
        assertThat(store.get(token)).isEmpty();
    }
}
