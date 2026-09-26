package ai.neargo.sharehub.auth;

import ai.neargo.sharehub.common.BizException;
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
 *
 * <h3>2026-09-26：断言从异常类型 + 中文字面量改成 i18n key</h3>
 * 9-25 的 i18n 批次把 {@code OtpService} 的 {@code IllegalArgumentException}
 * 换成了 {@link BizException}（业务拒绝落 400 而不是 500），**本类没跟着改**，
 * 于是 4 条从那天起一直红着 —— 已提交代码对已提交用例，没人认领。
 *
 * <p>顺带去掉 {@code hasMessageContaining("过期")}：现在过期与码错**故意回同一句**
 * （{@code error.otp.invalid} = 「验证码错误或已过期」）。区分开就等于告诉枚举者
 * 「这个号刚刚确实发过码」。所以过期那条改成用**状态**区分而不是用文案：
 * TTL 内同一个码能过、TTL 外不能过。
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
                .isInstanceOf(BizException.class).hasMessage("error.otp.invalid");
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
                .isInstanceOf(BizException.class).hasMessage("error.otp.invalid");
    }

    @Test
    @DisplayName("过期码不可用——用 TTL 前后的对比来证明，不靠文案里有没有「过期」二字")
    void expiredCodeRejected() throws Exception {
        // 先证明这个码本来是能过的：不然下面那条断言只说明「有什么东西失败了」
        OtpService live = prod(Duration.ofMinutes(5), Duration.ZERO, 5);
        assertThatCode(() -> live.verify(PHONE, live.issue(PHONE))).doesNotThrowAnyException();

        OtpService otp = prod(Duration.ofMillis(30), Duration.ZERO, 5);
        String code = otp.issue(PHONE);
        Thread.sleep(60);
        // 过期与码错**故意回同一句**：区分开等于告诉枚举者这个号刚刚确实发过码
        assertThatThrownBy(() -> otp.verify(PHONE, code))
                .isInstanceOf(BizException.class).hasMessage("error.otp.invalid");
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
                    .isInstanceOf(BizException.class).hasMessage("error.otp.invalid");
        }
        // 第 4 次：即便给的是正确的码，也已作废。这里回的是 too_many 而不是 invalid ——
        // 「你试太多次了，重新获取」和「码不对」对使用者是两件事
        assertThatThrownBy(() -> otp.verify(PHONE, code))
                .isInstanceOf(BizException.class).hasMessage("error.otp.too_many");
    }
}
