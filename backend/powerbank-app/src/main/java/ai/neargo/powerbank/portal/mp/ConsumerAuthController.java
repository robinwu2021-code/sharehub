package ai.neargo.powerbank.portal.mp;

import ai.neargo.powerbank.auth.TokenStore;
import ai.neargo.powerbank.user.consumer.ConsumerAuthService;
import ai.neargo.powerbank.user.consumer.OtpService;
import ai.neargo.powerbank.user.consumer.ConsumerLoginReq;
import ai.neargo.powerbank.user.consumer.ConsumerLoginVO;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * C 端统一认证端点（{@code /mp/auth/**}，permitAll）。App/小程序/H5 共用一套。
 */
@RestController
@RequestMapping("/mp/auth")
public class ConsumerAuthController {

    private final ConsumerAuthService authService;
    private final OtpService otpService;
    private final TokenStore tokenStore;

    public ConsumerAuthController(ConsumerAuthService authService, OtpService otpService, TokenStore tokenStore) {
        this.authService = authService;
        this.otpService = otpService;
        this.tokenStore = tokenStore;
    }

    /** 统一登录：grantType 分发（phone_otp/wechat_miniapp/wechat_oauth/apple/google）。 */
    @PostMapping("/login")
    public ConsumerLoginVO login(@RequestBody ConsumerLoginReq req) {
        return authService.login(req);
    }

    /** 发送 OTP（dev 返回固定码 000000 便于联调）。 */
    @PostMapping("/otp")
    public Map<String, Object> otp(@RequestBody Map<String, String> body) {
        String phone = body.get("phone");
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("手机号为空");
        }
        String code = otpService.issue(phone.trim());
        return Map.of("sent", true, "devCode", code);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth != null && auth.startsWith("Bearer ")) {
            tokenStore.revoke(auth.substring(7).trim());
        }
        return Map.of("ok", true);
    }
}
