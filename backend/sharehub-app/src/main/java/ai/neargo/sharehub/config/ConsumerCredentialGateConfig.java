package ai.neargo.sharehub.config;

import ai.neargo.sharehub.platform.cred.service.CredentialService;
import ai.neargo.sharehub.user.consumer.ConsumerCredentialGate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@code svc-platform} 的 {@link CredentialService} 接到 {@code svc-core} 的
 * {@link ConsumerCredentialGate} 端口上，realm 固定为 {@code CONSUMER}。
 *
 * <p>{@code sharehub-app} 是唯一同时依赖两个 svc 模块的地方 —— 与 {@code OtpGateConfig}、
 * {@code LoginMethodGateConfig} 同一个理由。
 *
 * <p><b>realm 在这一层钉死</b>，不作为端口参数往上传：传上去就意味着 C 端的代码
 * 有能力去校验员工的口令，而它没有任何理由需要那个能力。
 */
@Configuration
public class ConsumerCredentialGateConfig {

    /**
     * 「这个号没设过口令」时用来**烧掉一次 BCrypt** 的假散列。
     *
     * <p>不烧的话，没有凭据的号会在几毫秒内被拒，而有凭据的号要等 BCrypt 算完（上百毫秒）——
     * 这个耗时差本身就是一台「这个手机号注册过没有」的探测器，
     * 而登录接口是 permitAll、可以随便跑批。文案回成同一句只解决了一半。
     *
     * <p>值是对一个固定字符串的 BCrypt 散列，永远匹配不上任何真实输入。
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Bean
    public ConsumerCredentialGate consumerCredentialGate(CredentialService credentials,
                                                         PasswordEncoder encoder) {
        final String realm = ConsumerCredentialGate.REALM;
        return new ConsumerCredentialGate() {
            @Override
            public Check verify(String cUserNo, String rawPassword) {
                CredentialService.Check c = credentials.verify(realm, cUserNo, rawPassword);
                // null = 这个人没设过口令。这里把它转成显式的 hasCredential=false，
                // 而不是让上层去判 null —— 忘了判 null 的后果是 NPE 或「无口令即通过」。
                if (c == null) {
                    encoder.matches(rawPassword == null ? "" : rawPassword, DUMMY_HASH);  // 见 DUMMY_HASH
                    return new Check(false, false, "no_credential");
                }
                return new Check(c.ok(), true, c.reason());
            }

            @Override
            public void setPassword(String cUserNo, String rawPassword) {
                credentials.setPassword(realm, cUserNo, rawPassword);
            }

            @Override
            public void changePassword(String cUserNo, String oldPassword, String newPassword) {
                credentials.changePassword(realm, cUserNo, oldPassword, newPassword);
            }
        };
    }
}
