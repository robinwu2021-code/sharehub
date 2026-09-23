package ai.neargo.sharehub.agent.apply.service;

/**
 * 手机 OTP 端口（发码 + 验码）。
 *
 * <p><b>为什么要这个接口，而不是直接用 {@code user.consumer.OtpService}</b>：
 * 那个类在 {@code sharehub-svc-core}，而本模块（{@code sharehub-svc-platform}）
 * 只依赖 {@code common} + {@code api}。让 platform 依赖 core 会把两个 svc 模块焊死，
 * 拆分就静默失效了；下沉到 common 也不行 —— 那里有「零业务依赖」守卫（G4），
 * 一旦破例，<b>全部 svc-* 都会通过 common 间接依赖那个业务包</b>。
 *
 * <p>所以本模块只声明「我需要能发/验 OTP」，实现由 {@code sharehub-app}
 * （唯一同时看得见两边的模块）装配 —— 见 {@code OtpGateConfig}。
 *
 * <p>没装配时应用<b>起不来</b>（缺 bean），这是有意的：入驻自助入口没有 OTP 就是裸奔的。
 */
public interface OtpGate {

    /**
     * 发码；返回码本身。
     *
     * <p><b>返回值只供调用方决定是否展示</b> —— 生产由短信通道送达、接口不回传，
     * 只在 dev-mode 下回给联调用。
     *
     * @param normalizedPhone <b>规范化后</b>的手机号；必须与 {@link #verify} 用的是同一个形式
     * @throws IllegalStateException 距上次发码不足重发间隔
     */
    String issue(String normalizedPhone);

    /**
     * 校验并消费一次验证码；不通过抛 {@link IllegalArgumentException}。
     *
     * @param normalizedPhone <b>规范化后</b>的手机号 —— 必须与发码时用的是同一个形式，
     *                        否则「收到码了但验不过」
     */
    void verify(String normalizedPhone, String otp);
}
