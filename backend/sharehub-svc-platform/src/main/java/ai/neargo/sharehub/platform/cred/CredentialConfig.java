package ai.neargo.sharehub.platform.cred;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 口令编码器（P3b · B1）。
 *
 * <h2>为什么是 BCrypt cost 10</h2>
 * v4/06 §2.6 定死的。cost 是**有意的成本**：它让离线爆破变慢，
 * 代价是每次登录多几十毫秒 —— 这笔交换只在密码库泄露时才兑现，
 * 所以很容易被当成"可以调低的性能参数"。**不要调低**。
 *
 * <h2>这个 bean 现在还没有调用方</h2>
 * B1 只铺地基，**登录一行不改**：共享口令闸照常工作。
 * 它的第一个调用方是 B2（建号发一次性随机口令 + 自助改密）。
 *
 * <p>先注册 bean 的理由是：B2 与 B3 要在两个不同的模块里用它，
 * 到时各自 new 一个的话，两处的 cost 迟早不一致，
 * 而「另一处 cost 更低」这件事没有任何症状。
 */
@Configuration
public class CredentialConfig {

    /** v4/06 §2.6：BCrypt，cost 10。 */
    static final int BCRYPT_COST = 10;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_COST);
    }
}
