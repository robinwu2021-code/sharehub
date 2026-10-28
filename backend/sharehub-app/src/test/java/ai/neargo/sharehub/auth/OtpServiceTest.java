package ai.neargo.sharehub.auth;

import ai.neargo.sharehub.api.platform.port.NotifyPort;
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
        return new OtpService(new DevMode(false), okChannel(), ttl, resend, maxAttempts, TEMPLATE, TEMPLATE, 10);
    }

    static final String TEMPLATE = "code {code}";

    /** 投递成功的通道。 */
    static NotifyPort okChannel() {
        return recordingChannel(new java.util.ArrayList<>(), true);
    }

    /**
     * 记录每次投递的假通道。{@code send}（按 targetNo 的员工版）在 OTP 路径上不该被调用 ——
     * 收码人是个还没注册的手机号，不是员工，走那条一定得到 NOT_EMPLOYEE。
     */
    static NotifyPort recordingChannel(java.util.List<String[]> sink, boolean ok) {
        return new NotifyPort() {
            @Override public Delivery send(String targetNo, String ch, String scene, String content) {
                throw new AssertionError("OTP 不能走按员工号投递的 send()：收码人不是员工");
            }
            @Override public void push(String targetNo, String scene, String content) {
                throw new AssertionError("OTP 是短信，不是站内推送");
            }
            @Override public Delivery sendToTarget(String target, String ch, String scene, String content) {
                sink.add(new String[]{target, ch, scene, content});
                return ok ? new Delivery(true, null) : new Delivery(false, "SEND_ERROR");
            }
        };
    }

    private static String issue(OtpService o) {
        return o.issue(PHONE, "login").code();
    }

    @Test
    @DisplayName("生产：发的是真随机码，不是 000000")
    void issuesRandomCodeInProduction() {
        String code = issue(prod());
        assertThat(code).hasSize(6).containsOnlyDigits().isNotEqualTo("000000");
    }

    @Test
    @DisplayName("生产：固定码 000000 校验失败")
    void masterCodeRejectedInProduction() {
        OtpService otp = prod();
        issue(otp);
        assertThatThrownBy(() -> otp.verify(PHONE, "000000"))
                .isInstanceOf(BizException.class).hasMessage("error.otp.invalid");
    }

    @Test
    @DisplayName("dev-mode：固定码 000000 仍可用（本机联调）")
    void masterCodeAcceptedInDevMode() {
        OtpService otp = new OtpService(new DevMode(true), okChannel(),
                Duration.ofMinutes(5), Duration.ofSeconds(60), 5, TEMPLATE, TEMPLATE, 10);
        assertThatCode(() -> otp.verify(PHONE, "000000")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("真实码：校验成功后单次失效")
    void codeIsSingleUse() {
        OtpService otp = prod();
        String code = issue(otp);
        assertThatCode(() -> otp.verify(PHONE, code)).doesNotThrowAnyException();
        assertThatThrownBy(() -> otp.verify(PHONE, code))
                .isInstanceOf(BizException.class).hasMessage("error.otp.invalid");
    }

    @Test
    @DisplayName("过期码不可用——用 TTL 前后的对比来证明，不靠文案里有没有「过期」二字")
    void expiredCodeRejected() throws Exception {
        // 先证明这个码本来是能过的：不然下面那条断言只说明「有什么东西失败了」
        OtpService live = prod(Duration.ofMinutes(5), Duration.ZERO, 5);
        assertThatCode(() -> live.verify(PHONE, issue(live))).doesNotThrowAnyException();

        OtpService otp = prod(Duration.ofMillis(30), Duration.ZERO, 5);
        String code = issue(otp);
        Thread.sleep(60);
        // 过期与码错**故意回同一句**：区分开等于告诉枚举者这个号刚刚确实发过码
        assertThatThrownBy(() -> otp.verify(PHONE, code))
                .isInstanceOf(BizException.class).hasMessage("error.otp.invalid");
    }

    @Test
    @DisplayName("重发间隔内再发码被拒")
    void resendThrottled() {
        OtpService otp = prod();
        issue(otp);
        assertThatThrownBy(() -> issue(otp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("频繁");
    }

    @Test
    @DisplayName("校验次数超上限即作废（防 6 位码被枚举）")
    void codeVoidedAfterTooManyAttempts() {
        OtpService otp = prod(Duration.ofMinutes(5), Duration.ZERO, 3);
        String code = issue(otp);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> otp.verify(PHONE, "111111"))
                    .isInstanceOf(BizException.class).hasMessage("error.otp.invalid");
        }
        // 第 4 次：即便给的是正确的码，也已作废。这里回的是 too_many 而不是 invalid ——
        // 「你试太多次了，重新获取」和「码不对」对使用者是两件事
        assertThatThrownBy(() -> otp.verify(PHONE, code))
                .isInstanceOf(BizException.class).hasMessage("error.otp.too_many");
    }

    // ——————————————————————————————————————————————————————————————————
    //  投递（2026-09-26）：此前本类只生成码、从不投递，C 端登录在线上是死路
    // ——————————————————————————————————————————————————————————————————

    @Test
    @DisplayName("发码即投递：走 sendToTarget、带上码与场景模板号")
    void deliversThroughNotifyPort() {
        java.util.List<String[]> sent = new java.util.ArrayList<>();
        OtpService otp = new OtpService(new DevMode(false), recordingChannel(sent, true),
                Duration.ofMinutes(5), Duration.ofSeconds(60), 5, TEMPLATE, TEMPLATE, 10);

        OtpService.Issued r = otp.issue(PHONE, "register");

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)[0]).isEqualTo(PHONE);       // 收件人是手机号本身
        assertThat(sent.get(0)[1]).isEqualTo("SMS");
        assertThat(sent.get(0)[2]).isEqualTo("OTP_register");   // 分场景，便于在发送记录页统计
        assertThat(sent.get(0)[3]).isEqualTo("code " + r.code());
        assertThat(r.delivered()).isTrue();
        // 投递成功后码仍然有效
        assertThatCode(() -> otp.verify(PHONE, r.code())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("同一标识每日上限：到顶后拒发（重发间隔挡不住「每分钟一条发一整天」）")
    void dailyLimitPerIdentifier() {
        OtpService otp = new OtpService(new DevMode(false), okChannel(),
                Duration.ofMinutes(5), Duration.ZERO, 5, TEMPLATE, TEMPLATE, 2);
        otp.issue(PHONE, "login");
        otp.issue(PHONE, "login");
        assertThatThrownBy(() -> otp.issue(PHONE, "login")).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> otp.issueEmail("other@example.com", "login")).as("按标识计，别的标识不受影响")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("邮箱验证码（商家一期 B2）：走 EMAIL 通道；与同一串字符的手机号码表分开，互不顶掉")
    void emailCodesUseTheirOwnChannelAndKeys() {
        java.util.List<String[]> sent = new java.util.ArrayList<>();
        OtpService otp = new OtpService(new DevMode(false), recordingChannel(sent, true),
                Duration.ofMinutes(5), Duration.ofSeconds(60), 5, TEMPLATE, TEMPLATE, 10);

        OtpService.Issued r = otp.issueEmail("owner@example.com", "login");
        assertThat(sent.get(0)[0]).isEqualTo("owner@example.com");
        assertThat(sent.get(0)[1]).isEqualTo("EMAIL");
        assertThatThrownBy(() -> otp.verify("owner@example.com", r.code()))
                .as("邮箱的码不能拿去当手机号验").isInstanceOf(RuntimeException.class);
        assertThatCode(() -> otp.verifyEmail("owner@example.com", r.code())).doesNotThrowAnyException();
    }

    /**
     * 这条是本次修复的**要点**，不是顺手的清理。
     *
     * <p>投递失败却把码留在表里 = 用户被 {@code resend-interval} 锁在「发送过于频繁」上，
     * 去等一条永远不会到的短信：既收不到码、又不让重试。删掉之后才能立刻重试。
     */
    @Test
    @DisplayName("投递失败：作废该码并放开重发，不把用户锁在「发送过于频繁」里")
    void failedDeliveryDoesNotLockOutResend() {
        java.util.List<String[]> sent = new java.util.ArrayList<>();
        OtpService otp = new OtpService(new DevMode(false), recordingChannel(sent, false),
                Duration.ofMinutes(5), Duration.ofSeconds(60), 5, TEMPLATE, TEMPLATE, 10);

        OtpService.Issued r = otp.issue(PHONE, "login");
        assertThat(r.delivered()).isFalse();
        assertThat(r.reason()).isEqualTo("SEND_ERROR");

        // 1) 码已作废：万一它其实送到了，也不能拿它登录
        assertThatThrownBy(() -> otp.verify(PHONE, r.code()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("error.otp.invalid");
        // 2) 重发不被 60s 间隔挡住 —— 否则就是那个死局
        assertThatCode(() -> otp.issue(PHONE, "login")).doesNotThrowAnyException();
        assertThat(sent).hasSize(2);
    }
}
