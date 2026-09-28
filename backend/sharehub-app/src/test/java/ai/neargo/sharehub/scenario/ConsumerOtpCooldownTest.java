package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发码响应要带 cooldown（2026-09-28 真机实测）。
 *
 * <p>c-app 契约写着 {@code sendOtp(): {cooldown}}，服务端从来不回 —— 端上拿到 undefined：
 * 「发送」按钮不倒计时（用户反复点、只收到「重发太频繁」），而每秒 -1 的定时器算成 NaN 永远停不下来。
 * 数值必须与服务端真正拦重发的间隔一致，否则倒计时结束了点下去还是被拒。
 */
class ConsumerOtpCooldownTest extends ApiTestSupport {

    @Test
    @DisplayName("发码成功回 cooldown = 重发间隔；倒计时内再发被拒")
    void otpResponseCarriesTheResendCooldown() {
        String phone = "+9715" + (10000000 + new java.util.Random().nextInt(89999999));
        var first = post("/mp/auth/otp", Map.of("phone", phone), null).okData();
        assertThat(first.path("cooldown").asLong()).as("端上倒计时按它走").isEqualTo(60);

        var again = post("/mp/auth/otp", Map.of("phone", phone), null);
        assertThat(again.code()).as("倒计时内服务端也确实拦 —— 两边是同一个数").isNotZero();
        assertThat(again.status).as("是业务拒绝而不是未处理异常 500（此前如此：用户看到「系统错误」，还打一条 ERROR）")
                .isEqualTo(409);
        assertThat(again.msg()).isEqualTo("发送过于频繁，请稍后再试");
    }
}
