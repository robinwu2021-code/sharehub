package ai.neargo.sharehub.config;

import ai.neargo.sharehub.agent.apply.service.OtpGate;
import ai.neargo.sharehub.user.consumer.OtpService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@code svc-core} 的 {@link OtpService} 接到 {@code svc-platform} 的
 * {@link OtpGate} 端口上。
 *
 * <p>{@code sharehub-app} 是唯一同时依赖两个 svc 模块的地方，跨模块装配只能在这里做。
 * 两个 svc 模块之间不直接引用 —— 那会让模块拆分静默失效。
 *
 * <p><b>C 端注册与代理入驻共用同一套 OTP</b>：同一份限流、同一份有效期、同一个开发固定码开关。
 * 各写一套的结果一定是两套配置慢慢分叉，而分叉出来的那一套通常没人测。
 */
@Configuration
public class OtpGateConfig {

    @Bean
    public OtpGate otpGate(OtpService otpService) {
        return new OtpGate() {
            @Override public String issue(String normalizedPhone) { return otpService.issue(normalizedPhone); }
            @Override public void verify(String normalizedPhone, String otp) { otpService.verify(normalizedPhone, otp); }
        };
    }
}
