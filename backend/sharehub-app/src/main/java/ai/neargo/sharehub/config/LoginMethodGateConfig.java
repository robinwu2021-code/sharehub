package ai.neargo.sharehub.config;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.LoginSetting;
import ai.neargo.sharehub.platform.sys.service.LoginSettingService;
import ai.neargo.sharehub.user.consumer.LoginMethodGate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@code svc-platform} 的 {@link LoginSettingService} 接到 {@code svc-core} 的
 * {@link LoginMethodGate} 端口上。
 *
 * <p>{@code sharehub-app} 是唯一同时依赖两个 svc 模块的地方，跨模块装配只能在这里做 ——
 * 与 {@code OtpGateConfig} 同一个理由（两个 svc 互相引用会让模块拆分静默失效）。
 */
@Configuration
public class LoginMethodGateConfig {

    /** 词表与 {@code ConsumerLoginStrategy#grantType()} 对齐。 */
    static boolean allows(LoginSetting s, String grantType) {
        return switch (grantType == null ? "" : grantType) {
            case "phone_otp" -> Boolean.TRUE.equals(s.otpEnabled());
            case "password" -> Boolean.TRUE.equals(s.passwordEnabled());
            case "apple" -> Boolean.TRUE.equals(s.appleEnabled());
            case "google" -> Boolean.TRUE.equals(s.googleEnabled());
            /*
             * 微信两种方式表里没有对应列（`sys_login_setting` 只有四列，
             * 需求 OPS-SYS-07 的备注也写着「表缺微信开关列」）。
             * 这里放行而不是拒绝：拒绝等于本次顺手停掉小程序登录，
             * 那是个产品决定，不该由一个补开关的改动附带做掉。
             */
            case "wechat_miniapp", "wechat_oauth" -> true;
            default -> false;   // 未知方式一律拒：新增策略必须同时想清楚它的开关
        };
    }

    @Bean
    public LoginMethodGate loginMethodGate(LoginSettingService settings) {
        return (country, grantType) -> {
            LoginSetting s = settings.effective(country);
            if (s == null) {
                /*
                 * 连默认行 '*' 都没有。**不静默放行** —— 那会让这个门禁在配置缺失时
                 * 恰好等于不存在，而这正是它要修的那个状态（开关拨了等于没拨）。
                 * V126 会幂等补上默认行；真缺了就该有人立刻知道。
                 */
                throw BizException.conflict("error.auth.login_setting_missing");
            }
            return allows(s, grantType);
        };
    }
}
