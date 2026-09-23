package ai.neargo.sharehub.auth;

import ai.neargo.sharehub.user.consumer.OtpService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link OtpService} 单元测试（TDD-auth-security-hotfix T2）。
 *
 * <p>放在 sharehub-app 的测试源码下：svc-core 暂无 test 依赖（属 B2「卡口与测试地基」）。
 */
class OtpServiceTest {

    private static final String PHONE = "+971500000000";

    /** 生产姿态：dev-mode 关。 */
    private static OtpService prod() {
        return prod(Duration.ofMinutes(5), Duration.ofSeconds(60), 5);
    }

    private static OtpService prod(Duration ttl, Duration resend, int maxAttempts) {
        return new OtpService(new DevMode(false), ttl, resend, maxAttempts);
    }

    @Test
    @DisplayName("生产：发的是真随机码，不是 000000")
    void issuesRandomCodeInProduction() {
        String code = prod().issue(PHONE);
        assertThat(code).hasSize(6).containsOnlyDigits().isNotEqualTo("000000");
    }

    @Test
    @DisplayName("生产：固定码 000000 校验失败")
    void masterCodeRejectedInProduction() {
        OtpService otp = prod();
        otp.issue(PHONE);
        assertThatThrownBy(() -> otp.verify(PHONE, "000000"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("dev-mode：固定码 000000 仍可用（本机联调）")
    void masterCodeAcceptedInDevMode() {
        OtpService otp = new OtpService(new DevMode(true),
                Duration.ofMinutes(5), Duration.ofSeconds(60), 5);
        assertThatCode(() -> otp.verify(PHONE, "000000")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("真实码：校验成功后单次失效")
    void codeIsSingleUse() {
        OtpService otp = prod();
        String code = otp.issue(PHONE);
        assertThatCode(() -> otp.verify(PHONE, code)).doesNotThrowAnyException();
        assertThatThrownBy(() -> otp.verify(PHONE, code))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("过期码不可用")
    void expiredCodeRejected() throws Exception {
        OtpService otp = prod(Duration.ofMillis(30), Duration.ZERO, 5);
        String code = otp.issue(PHONE);
        Thread.sleep(60);
        assertThatThrownBy(() -> otp.verify(PHONE, code))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过期");
    }

    @Test
    @DisplayName("重发间隔内再发码被拒")
    void resendThrottled() {
        OtpService otp = prod();
        otp.issue(PHONE);
        assertThatThrownBy(() -> otp.issue(PHONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("频繁");
    }

    @Test
    @DisplayName("校验次数超上限即作废（防 6 位码被枚举）")
    void codeVoidedAfterTooManyAttempts() {
        OtpService otp = prod(Duration.ofMinutes(5), Duration.ZERO, 3);
        String code = otp.issue(PHONE);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> otp.verify(PHONE, "111111"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // 第 4 次：即便给的是正确的码，也已作废
        assertThatThrownBy(() -> otp.verify(PHONE, code))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
