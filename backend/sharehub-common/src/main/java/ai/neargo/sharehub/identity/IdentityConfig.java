package ai.neargo.sharehub.identity;

import ai.neargo.sharehub.auth.DevMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 登录标识的规范化与哈希装配。
 *
 * <p>两个 bean 都在构造期做 fail-fast 校验：pepper 没配 / 太短、默认区号没配，
 * 应用**起不来**。这是有意的 —— 这两项配错的后果都是静默的
 * （hash 对不上 → 注册能成登录查不到），等到发现时库里已经是脏的。
 */
@Configuration
public class IdentityConfig {

    /**
     * 开发/测试用的固定 pepper —— 与 {@code OtpService} 的固定码 {@code 000000} 同一个取舍：
     * <b>只在 dev-mode 开启时生效</b>，生产（dev-mode 关）没配就是起不来。
     *
     * <p>为什么不给一个普通默认值：那等于所有部署共用同一个 pepper，
     * 拿到任意一份代码的人就能离线穷举出全部手机号 —— 等于没有 pepper。
     */
    static final String DEV_PEPPER = "dev-only-pepper-do-not-use-in-production!";

    @Bean
    public IdentifierHasher identifierHasher(
            DevMode devMode,
            @Value("${sharehub.security.identity-pepper:}") String pepper,
            @Value("${sharehub.security.identity-pepper-version:1}") int version) {
        String effective = (pepper == null || pepper.isBlank()) && devMode.isEnabled()
                ? DEV_PEPPER : pepper;
        return new IdentifierHasher(effective, version);
    }

    @Bean
    public IdentifierNormalizer identifierNormalizer(
            @Value("${sharehub.security.default-calling-code:}") String callingCode) {
        return new IdentifierNormalizer(callingCode);
    }
}
