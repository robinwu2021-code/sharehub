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

    /**
     * 开发/测试用的固定字段密钥，规则同 {@link #DEV_PEPPER}：只在 dev-mode 生效。
     * 由固定串派生，**不是**生产可用的密钥。
     */
    static final String DEV_PII_KEY_SEED = "dev-only-pii-key-do-not-use-in-production";

    /**
     * 敏感字段加密（收款账户完整 IBAN 等）。密钥 {@code sharehub.security.pii-key}：32 字节的 base64。
     * 没配且不在 dev-mode 时加密关闭（见 {@link FieldCipher} 类注释），并在启动日志里说清楚。
     */
    @Bean
    public FieldCipher fieldCipher(DevMode devMode, @Value("${sharehub.security.pii-key:}") String keyB64) {
        if (keyB64 != null && !keyB64.isBlank()) {
            return new FieldCipher(java.util.Base64.getDecoder().decode(keyB64.trim()));
        }
        if (devMode.isEnabled()) {
            try {
                return new FieldCipher(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(DEV_PII_KEY_SEED.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        org.slf4j.LoggerFactory.getLogger(IdentityConfig.class).warn(
                "未配置 sharehub.security.pii-key：收款账户只存掩码，付款任务无法导出完整账号。"
                        + "请运维生成 32 字节随机密钥（openssl rand -base64 32）配到该项后重启");
        return new FieldCipher(null);
    }

    @Bean
    public IdentifierNormalizer identifierNormalizer(
            @Value("${sharehub.security.default-calling-code:}") String callingCode) {
        return new IdentifierNormalizer(callingCode);
    }
}
