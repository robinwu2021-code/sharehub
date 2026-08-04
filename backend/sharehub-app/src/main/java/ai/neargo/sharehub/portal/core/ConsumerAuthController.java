package ai.neargo.sharehub.portal.core;

import ai.neargo.sharehub.auth.TokenStore;
import ai.neargo.sharehub.user.consumer.ConsumerAuthService;
import ai.neargo.sharehub.user.consumer.OtpService;
import ai.neargo.sharehub.user.consumer.ConsumerLoginReq;
import ai.neargo.sharehub.user.consumer.ConsumerLoginVO;
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

    /**
     * 注册 = OTP 验证 + 建户 + 直接发 token（复用 phone_otp 登录链路，带显式昵称）。
     * {@code password} 字段**不受理**：消费者凭据库（pb_auth 分库）未建（[未完成清单 B6]），
     * 出参 {@code passwordStored=false} 如实声明，端上按 OTP 登录引导。
     */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, String> body) {
        String phone = body.get("phone");
        String otp = body.get("otp");
        if (phone == null || phone.isBlank()) throw new IllegalArgumentException("手机号为空");
        if (otp == null || otp.isBlank()) throw new IllegalArgumentException("验证码为空");
        ConsumerLoginReq req = new ConsumerLoginReq("phone_otp", null, phone.trim(), otp.trim(),
                body.get("countryCode"), null, null, null, null, null,
                body.get("nickname"), null);
        ConsumerLoginVO vo = authService.login(req);
        return Map.of("token", vo.token(), "cUserNo", vo.cUserNo(),
                "nickname", body.get("nickname") == null ? "" : body.get("nickname"),
                "isNew", vo.isNew(), "passwordStored", false);
    }

    /** 密码重置：**能力未开通**（凭据库 pb_auth 未建，[未完成清单 B6]）——如实 400，不假 ok。 */
    @PostMapping("/password/reset")
    public Map<String, Object> resetPassword(@RequestBody(required = false) Map<String, String> body) {
        throw new IllegalArgumentException("密码能力未开通：消费者凭据库(pb_auth)未建，请使用手机验证码登录");
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth != null && auth.startsWith("Bearer ")) {
            tokenStore.revoke(auth.substring(7).trim());
        }
        return Map.of("ok", true);
    }
}
