package ai.neargo.sharehub.portal.core;

import ai.neargo.sharehub.auth.DevMode;
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
    private final DevMode devMode;

    public ConsumerAuthController(ConsumerAuthService authService, OtpService otpService,
                                  TokenStore tokenStore, DevMode devMode) {
        this.authService = authService;
        this.otpService = otpService;
        this.tokenStore = tokenStore;
        this.devMode = devMode;
    }

    /** 统一登录：grantType 分发（phone_otp/wechat_miniapp/wechat_oauth/apple/google）。 */
    @PostMapping("/login")
    public ConsumerLoginVO login(@RequestBody ConsumerLoginReq req) {
        return authService.login(req);
    }

    /**
     * 发送 OTP。
     *
     * <p><b>2026-09-23 安全止血</b>：此前**无条件把验证码明文回传**给调用方，
     * 等于任何人都能以任意手机号登录（线上生效过）。现在只有 {@link DevMode} 开启时才回传，
     * 生产只回 {@code {"sent": true}}，码由短信通道送达。
     */
    @PostMapping("/otp")
    public Map<String, Object> otp(
            @RequestBody ai.neargo.sharehub.user.consumer.ConsumerAuthReqs.OtpReq body) {
        String phone = body == null ? null : body.phone();
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("手机号为空");
        }
        // ⚠️ countryCode / scene 收进契约了但**仍未参与计算** —— 见 OtpReq 的注释：
        // 码表与 uid 都只按 phone 派生，+971 与 +966 的同号是同一个账号、同一个码。
        // 修它要改 uid 的组成（给存量账号改身份），属于要先拍板的迁移，不在本次。
        String code = otpService.issue(phone.trim());
        return devMode.isEnabled()
                ? Map.of("sent", true, "devCode", code)
                : Map.of("sent", true);
    }

    /**
     * 注册 = OTP 验证 + 建户 + 直接发 token（复用 phone_otp 登录链路，带显式昵称）。
     * {@code password} 字段**不受理**：消费者凭据库（pb_auth 分库）未建（[未完成清单 B6]），
     * 出参 {@code passwordStored=false} 如实声明，端上按 OTP 登录引导。
     */
    @PostMapping("/register")
    public Map<String, Object> register(
            @RequestBody ai.neargo.sharehub.user.consumer.ConsumerAuthReqs.RegisterReq body) {
        String phone = body == null ? null : body.phone();
        String otp = body == null ? null : body.otp();
        if (phone == null || phone.isBlank()) throw new IllegalArgumentException("手机号为空");
        if (otp == null || otp.isBlank()) throw new IllegalArgumentException("验证码为空");
        ConsumerLoginReq req = new ConsumerLoginReq("phone_otp", null, phone.trim(), otp.trim(),
                body.countryCode(), null, null, null, null, null,
                body.nickname(), null);
        ConsumerLoginVO vo = authService.login(req);
        return Map.of("token", vo.token(), "cUserNo", vo.cUserNo(),
                "nickname", body.nickname() == null ? "" : body.nickname(),
                "isNew", vo.isNew(), "passwordStored", false);
    }

    /** 密码重置：**能力未开通**（凭据库 pb_auth 未建，[未完成清单 B6]）——如实 400，不假 ok。 */
    @PostMapping("/password/reset")
    public Map<String, Object> resetPassword(
            @RequestBody(required = false) ai.neargo.sharehub.user.consumer.ConsumerAuthReqs.ResetPwdReq body) {
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
