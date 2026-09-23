package ai.neargo.sharehub.auth;

import ai.neargo.sharehub.auth.store.LocalCacheTokenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `token-store` 开关生效的回归（TDD-auth-security-hotfix T5）。
 *
 * <p>守的是一个静默失效的缺陷：{@code TokenStoreConfig} 的开关键名此前写成
 * {@code powerbank.auth.token-store}，而配置文件用的是 {@code sharehub.auth.token-store} ——
 * 键名对不上，加上 memory 分支 {@code matchIfMissing = true}，
 * **配 redis / mysql / ehcache 都会静默回落到内存实现**（当时还没有 TTL）。
 * 改回键名后这条断言才有意义；没有它，下次有人改前缀又会悄悄失效。
 */
@SpringBootTest(properties = {
        "sharehub.auth.token-store=ehcache",
        "sharehub.dev-mode.enabled=false",
})
class TokenStoreWiringTest {

    @Autowired
    TokenStore tokenStore;

    @Test
    @DisplayName("配 ehcache 就要真的装到 LocalCacheTokenStore，而不是静默回落内存实现")
    void switchTakesEffect() {
        assertThat(tokenStore).isInstanceOf(LocalCacheTokenStore.class);
    }
}
