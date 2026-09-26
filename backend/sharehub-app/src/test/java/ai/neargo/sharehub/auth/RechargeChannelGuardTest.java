package ai.neargo.sharehub.auth;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.trade.pay.service.PaymentService;
import ai.neargo.sharehub.user.asset.service.impl.RechargeServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 充值在**桩支付**上必须被拒（2026-09-26）。
 *
 * <h3>它拦的是什么</h3>
 * {@code StubPaymentPort} 是唯一的 {@code PaymentPort} 实现、无条件装配、一律模拟成功。
 * 而 {@code RechargeServiceImpl} 的逻辑本身是对的 —— 它检查 {@code PAID} 之后才
 * {@code wallets.credit()}。两件都对的事凑在一起就是：
 * <b>任何跑这套构建的环境里，登录后的用户都能免费充值</b>，而余额是能花出去的。
 *
 * <p>2026-09-26 当时够不到的唯一原因是 C 端登录不了（短信通道未接）——
 * 而「接短信通道」正是那时的下一个待办。**接通的同一天它就会变成可达的。**
 *
 * <h3>为什么用单测而不是场景用例</h3>
 * 场景用例跑在 {@code sharehub.dev-mode.enabled=true} 下（闸在 dev-mode 开着时故意放行，
 * 本机联调要用），所以它证明不了生产姿态。这里直接构造 {@code new DevMode(false)}。
 *
 * <p>顺带钉住一件事：**闸必须在任何 mapper 之前**。下面几个依赖全传 {@code null} ——
 * 只要闸跑在第一句，就不会有 NPE；哪天有人把它挪到参数校验之后，这条会以 NPE 变红。
 */
class RechargeChannelGuardTest {

    /** 只回答 {@code simulated()} 的假编排器；其余方法被调到就说明闸没拦住。 */
    private static PaymentService channel(boolean simulated) {
        return (PaymentService) Proxy.newProxyInstance(
                PaymentService.class.getClassLoader(),
                new Class<?>[]{PaymentService.class},
                (proxy, method, args) -> {
                    if ("simulated".equals(method.getName())) return simulated;
                    throw new AssertionError("闸没拦住，已经走到了 " + method.getName());
                });
    }

    private static RechargeServiceImpl service(boolean simulated, boolean devMode) {
        return new RechargeServiceImpl(null, null, null, channel(simulated), null, new DevMode(devMode));
    }

    @Test
    @DisplayName("生产姿态 + 桩通道 → 拒绝充值（不是悄悄给用户加余额）")
    void refusesOnStubChannelInProduction() {
        assertThatThrownBy(() -> service(true, false).recharge("CU0001", "PKG001"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("error.recharge.channel_not_ready");
    }

    @Test
    @DisplayName("闸跑在参数校验之前：packageNo 为空也照样先拒通道")
    void guardRunsBeforeAnythingElse() {
        // 若闸被挪到 packageNo 校验之后，这里会得到 package_required 而不是 channel_not_ready
        assertThatThrownBy(() -> service(true, false).recharge("CU0001", null))
                .hasMessageContaining("error.recharge.channel_not_ready");
    }

    @Test
    @DisplayName("dev-mode 开着时放行（本机联调要用；dev-mode 本身是 fail-closed 的）")
    void allowsWhenDevModeOn() {
        // 放行后会继续往下走到 packageNo 校验 —— 拿到的不该是通道那句
        assertThatThrownBy(() -> service(true, true).recharge("CU0001", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("error.recharge.package_required");
    }

    @Test
    @DisplayName("真实通道 → 闸不该挡（它只针对桩）")
    void realChannelPassesTheGuard() {
        assertThatThrownBy(() -> service(false, false).recharge("CU0001", null))
                .hasMessageContaining("error.recharge.package_required");
    }

    @Test
    @DisplayName("桩自己如实回 simulated=true —— 真实实现不必覆写，新加的桩必须覆写")
    void stubDeclaresItself() {
        assertThat(new ai.neargo.sharehub.trade.pay.StubPaymentPort().simulated()).isTrue();
    }
}
