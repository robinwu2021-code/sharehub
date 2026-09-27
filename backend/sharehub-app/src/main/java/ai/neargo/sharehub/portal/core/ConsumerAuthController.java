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
    private final ai.neargo.sharehub.identity.IdentifierNormalizer normalizer;
    private final ai.neargo.sharehub.platform.sys.service.LoginSettingService loginSettings;
    private final ai.neargo.sharehub.user.consumer.ConsumerPasswordService passwords;

    public ConsumerAuthController(ConsumerAuthService authService, OtpService otpService,
                                  TokenStore tokenStore, DevMode devMode,
                                  ai.neargo.sharehub.identity.IdentifierNormalizer normalizer,
                                  ai.neargo.sharehub.platform.sys.service.LoginSettingService loginSettings,
                                  ai.neargo.sharehub.user.consumer.ConsumerPasswordService passwords) {
        this.authService = authService;
        this.otpService = otpService;
        this.tokenStore = tokenStore;
        this.devMode = devMode;
        this.normalizer = normalizer;
        this.loginSettings = loginSettings;
        this.passwords = passwords;
    }

    /**
     * 可用登录方式（需求 OPS-SYS-07，{@code sys_login_setting}）。
     *
     * <p>端上据此渲染页签与入口。此前**没有这个端点**，所以 c-app 无条件画出「密码」页签 ——
     * 而后端连 {@code password} 这个 grantType 都没有，用户点下去吃一个 400。
     * 开关本身自 V5 就存在，四列一个都没人读：运营台拨了等于没拨。
     *
     * <p>⚠️ 出参是**建议**，不是保护：真正的闸在 {@code ConsumerAuthService.login}
     * （见那里的注释）。端上按它隐入口，后端照样要拦。
     *
     * @param country ISO alpha-2；空或查不到则回落默认行 {@code '*'}
     */
    @GetMapping("/methods")
    public Map<String, Object> methods(@RequestParam(required = false) String country) {
        var s = loginSettings.effective(country);
        if (s == null) {
            // 与 LoginMethodGate 同一个取舍：不假装「全开」，也不假装「全关」
            throw ai.neargo.sharehub.common.BizException.conflict("error.auth.login_setting_missing");
        }
        return Map.of(
                "country", s.country() == null ? "*" : s.country(),
                "otp", Boolean.TRUE.equals(s.otpEnabled()),
                "password", Boolean.TRUE.equals(s.passwordEnabled()),
                "apple", Boolean.TRUE.equals(s.appleEnabled()),
                "google", Boolean.TRUE.equals(s.googleEnabled()),
                "forceRealName", Boolean.TRUE.equals(s.forceRealName()),
                "otpExpireSec", s.otpExpireSec() == null ? 300 : s.otpExpireSec());
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
        // 归一在这里做（与代理侧 AgentLoginServiceImpl / ApplyServiceImpl 同一个方法），
        // 之后整条链路（码表键、uid）用的都是它的输出 —— ADR-030 §4.1。
        // 区号：phone 自带 + 以它为准 → 否则 countryCode → 都没有才用默认区号（9-27 拍板，
        // 此前 countryCode 没人读，+971 与 +966 的同号是同一个账号）。登录/注册/重置必须同一个方法。
        String normalized = normalizer.phone(phone, body.countryCode());
        OtpService.Issued issued = otpService.issue(normalized, body.scene());
        // sent 如实回传投递结果：投递失败时码已作废，端上该提示重试而不是等短信。
        // cooldown：端上倒计时的秒数，与服务端「重发太频繁」同一个数。此前不回 —— c-app 契约写着有、
        // 实际拿到 undefined：按钮不倒计时，而那个每秒 -1 的定时器算成 NaN 永不停（真机实测）。
        // 投递失败回 0：码已作废，不该把人锁 60 秒去等一条不会到的短信（同 OtpService 的约定）。
        long cooldown = issued.delivered() ? otpService.resendIntervalSeconds() : 0L;
        return devMode.isEnabled()
                ? Map.of("sent", issued.delivered(), "reason", issued.reason() == null ? "" : issued.reason(),
                         "devCode", issued.code(), "cooldown", cooldown)
                : (issued.delivered() ? Map.of("sent", true, "cooldown", cooldown)
                                      : Map.of("sent", false, "reason", issued.reason(), "cooldown", cooldown));
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
        // 第 5 位现在是 country(ISO)，而 RegisterReq 收的 countryCode 是拨号码（+971）——
        // 两者不是一回事，塞进去只会让门禁按一个不存在的 ISO 去查、白白回落默认行。
        // 注册走的是 phone_otp，默认行 otp_enabled=1，门禁按默认行判即正确。
        ConsumerLoginReq req = new ConsumerLoginReq("phone_otp", null, phone, otp.trim(),
                null, null, null, null, null, null, null,
                body.nickname(), null, body.countryCode());
        ConsumerLoginVO vo = authService.login(req);
        /*
         * 口令现在**受理**了（realm=CONSUMER 的凭据，见 TDD-C端密码登录与凭据）。
         * 空着就不设 —— 注册不强制设口令：OTP 登录本身就是完整的登录方式，
         * 逼用户当场想一个只会让他想一个弱的。
         * 密码登录开关关着时 setOnRegister 会抛，于是「注册成功但口令没存」不会静默发生。
         */
        boolean stored = false;
        if (body.password() != null && !body.password().isBlank()) {
            passwords.setOnRegister(vo.cUserNo(), body.password(), body.country());
            stored = true;
        }
        return Map.of("token", vo.token(), "cUserNo", vo.cUserNo(),
                "nickname", body.nickname() == null ? "" : body.nickname(),
                "isNew", vo.isNew(), "passwordStored", stored);
    }

    /**
     * 忘记密码：**验 OTP → 用户自设新口令**。
     *
     * <p>此前这里如实 400（「凭据库 pb_auth 未建」）。那个理由 2026-09-26 起不再成立 ——
     * {@code cred_credential} 早已存在且按 realm 参数化，本能力只是多一个 {@code CONSUMER}
     * realm，不需要等 {@code pb_auth} 分库（那是架构级 D4，与此解耦）。
     *
     * <p>不回 token：重设完让用户**用新口令登一次**。直接发 token 会让「改完密码顺手登进去」
     * 掩盖掉新口令其实没存成的情况。
     */
    @PostMapping("/password/reset")
    public Map<String, Object> resetPassword(
            @RequestBody ai.neargo.sharehub.user.consumer.ConsumerAuthReqs.ResetPwdReq body) {
        if (body == null || body.phone() == null || body.phone().isBlank()) {
            throw new IllegalArgumentException("手机号为空");
        }
        passwords.resetByOtp(body.phone(), body.countryCode(), body.otp(), body.password(), body.country());
        return Map.of("ok", true);
    }

    // 改密**不在这里**：本控制器挂在 /mp/auth/**，而那整段是 permitAll（登录前的接口）。
    // 改密要求已登录，所以它在 MpUserController 的 POST /mp/user/password ——
    // 放这里就得在 SecurityConfig 里给 /mp/auth/** 开一个 authenticated 例外，
    // 而一条与周围规则相反的例外，是下一个人最容易读漏的东西。

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth != null && auth.startsWith("Bearer ")) {
            tokenStore.revoke(auth.substring(7).trim());
        }
        return Map.of("ok", true);
    }
}
