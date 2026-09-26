package ai.neargo.sharehub.config;

import ai.neargo.sharehub.platform.sys.dto.SysDtos.LoginSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `allows` 的单测 —— 开关词表到 grantType 的映射。
 *
 * <p>为什么单测而不只靠场景用例：未知 grantType 在场景里走不到这里
 * （`strategies.get()==null` 先抛），而 `default -> false` 正是本方法最要紧的一支 ——
 * 新增一个登录策略却忘了给它配开关时，它决定的是「默认放行」还是「默认拒绝」。
 */
class LoginMethodAllowsTest {

    // 同包放置，为的是直测包级可见的 allows() —— 不为测试放宽生产代码的可见性。
    private static boolean allows(LoginSetting s, String grantType) {
        return LoginMethodGateConfig.allows(s, grantType);
    }

    /** otp 开、password 开、apple 关、google 关。 */
    private static LoginSetting s() {
        return new LoginSetting("AE", "UAE", true, true, false, false, 300, 10, false);
    }

    @Test
    @DisplayName("四个开关各自映射到对应 grantType")
    void mapsEachSwitch() {
        assertThat(allows(s(), "phone_otp")).isTrue();
        assertThat(allows(s(), "password")).isTrue();
        assertThat(allows(s(), "apple")).isFalse();
        assertThat(allows(s(), "google")).isFalse();
    }

    @Test
    @DisplayName("未知 grantType → 默认拒（不是默认放行）")
    void unknownDefaultsToDeny() {
        assertThat(allows(s(), "telepathy"))
                .as("新增策略忘了配开关时，默认必须是拒 —— 默认放行等于这个开关可以被绕过")
                .isFalse();
        assertThat(allows(s(), null)).isFalse();
        assertThat(allows(s(), "")).isFalse();
    }

    @Test
    @DisplayName("微信两种放行：表里没有对应列，停掉它是产品决定不是本次附带")
    void wechatPassesUntilTableHasColumns() {
        assertThat(allows(s(), "wechat_miniapp")).isTrue();
        assertThat(allows(s(), "wechat_oauth")).isTrue();
    }

    @Test
    @DisplayName("开关列为 null 按关处理（历史行可能没值）")
    void nullSwitchMeansOff() {
        LoginSetting partial = new LoginSetting("ZZ", null, null, null, null, null, null, null, null);
        assertThat(allows(partial, "phone_otp")).isFalse();
        assertThat(allows(partial, "password")).isFalse();
    }
}
