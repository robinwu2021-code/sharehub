package ai.neargo.sharehub.auth;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 开发便利总开关（{@code sharehub.dev-mode.enabled}，默认 <b>false</b>）。
 *
 * <p>被它门禁的三件事，**每一件单独都足以让任何人登录本系统**，所以合成一个开关：
 * 三个开关意味着三处可漏配，而「安全默认」要维护三遍。
 * <ul>
 *   <li>固定验证码 {@code 000000} 可通过校验（{@code OtpService}）；</li>
 *   <li>{@code POST /mp/auth/otp} 回传验证码明文（{@code ConsumerAuthController}）；</li>
 *   <li>未配置 {@code sharehub.admin.password} 时允许免密登录运营端（{@code AuthController}）。</li>
 * </ul>
 *
 * <p><b>生产绝不可开。</b>不配置即全关 —— 漏配的后果是「登不进去」而不是「谁都能进」。
 * 开启时在启动日志打 WARN 横幅，避免它被悄悄带上生产还没人发现
 * （2026-09-23 之前正是这样：固定码在所有环境无条件生效，线上任何人可登录任意手机号）。
 */
@Component
public class DevMode {

    private static final Logger log = LoggerFactory.getLogger(DevMode.class);

    private final boolean enabled;

    public DevMode(@Value("${sharehub.dev-mode.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    /** 开发便利功能是否放行。生产恒为 false。 */
    public boolean isEnabled() {
        return enabled;
    }

    @PostConstruct
    void warnIfEnabled() {
        if (enabled) {
            log.warn("""
                    ============================================================
                     DEV MODE 已开启（sharehub.dev-mode.enabled=true）
                     固定验证码 000000 可用 · OTP 明文回传 · 免密登录运营端
                     **此配置绝不可出现在生产环境**
                    ============================================================""");
        }
    }
}
