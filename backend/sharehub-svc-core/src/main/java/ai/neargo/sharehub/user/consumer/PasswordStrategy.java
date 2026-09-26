package ai.neargo.sharehub.user.consumer;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.identity.IdentifierHasher;
import ai.neargo.sharehub.identity.IdentifierNormalizer;
import org.springframework.stereotype.Component;

/**
 * 手机号 + 口令登录（{@code grantType=password}）。
 *
 * <p>此前<b>后端没有这个策略</b>，而 c-app 的登录页无条件画着「密码」页签、
 * 发的就是 {@code grantType:"password"} —— 路径与形状都对，对齐卡口全绿，
 * 用户点下去吃一个 {@code grant_type_unsupported} 的 400。
 *
 * <h2>与 OTP 登录的两处关键差别</h2>
 * <ol>
 *   <li><b>不建户</b>。OTP 登录是「验证手机号所有权 → 没有就建」；口令登录的前提是
 *       账号已存在。查不到身份就拒，<b>不能</b>让它走到 {@code upsertByIdentity} 去新建 ——
 *       那会凭一个随便填的口令建出一个空账号。</li>
 *   <li><b>三种失败必须同一句文案、同一个耗时量级</b>：号不存在 / 没设过口令 / 口令错。
 *       区分开来就是一台「这个手机号注册过没有」的探测器，而本端点是 permitAll、
 *       可以随便跑批。耗时的对齐在 {@code ConsumerCredentialGateConfig} 里做（烧一次 BCrypt）。</li>
 * </ol>
 *
 * <p>手机号归一与 uid 派生**必须与 {@link PhoneOtpStrategy} 用同一个方法**
 * （ADR-030 §4.1）—— 各写一遍的后果是「用验证码能登、用口令登不进去」，且不报任何错。
 */
@Component
public class PasswordStrategy implements ConsumerLoginStrategy {

    /** 三种失败共用这一句。**不要**为了"友好"拆开，见类注释。 */
    private static final String LOGIN_FAILED = "error.auth.login_failed";

    private final ConsumerCredentialGate credentials;
    private final ConsumerPasswordService passwords;
    private final IdentifierNormalizer normalizer;
    private final IdentifierHasher hasher;

    public PasswordStrategy(ConsumerCredentialGate credentials, ConsumerPasswordService passwords,
                            IdentifierNormalizer normalizer, IdentifierHasher hasher) {
        this.credentials = credentials;
        this.passwords = passwords;
        this.normalizer = normalizer;
        this.hasher = hasher;
    }

    @Override
    public String grantType() {
        return "password";
    }

    @Override
    public ResolvedIdentity authenticate(ConsumerLoginReq req) {
        if (req.phone() == null || req.phone().isBlank()) {
            throw BizException.badRequest(LOGIN_FAILED);
        }
        String phone = normalizer.phone(req.phone());     // 与 PhoneOtpStrategy 同一个方法
        String uid = hasher.hash(phone);

        // 查找口径与「忘记密码」共用一份，见 ConsumerPasswordService#findCUserNo
        String cUserNo = passwords.findCUserNo(phone, req.phone());
        /*
         * 号不存在时**也要走一遍校验**，用一个不存在的主体号 —— 目的只是让耗时与真实路径一致
         * （网关那边会烧一次 BCrypt）。直接 return 的话，「秒失败 vs 等一会儿才失败」
         * 同样能区分出号存不存在。同理由见代理侧 AgentLoginServiceImpl。
         */
        ConsumerCredentialGate.Check check =
                credentials.verify(cUserNo == null ? "__absent__" : cUserNo, req.password());
        if (cUserNo == null || !check.ok()) {
            throw BizException.badRequest(LOGIN_FAILED);
        }
        /*
         * 回一个「已存在」的身份：providerUid 就是查到这条身份的那个 uid，
         * 于是 upsertByIdentity 必定命中第一支（同渠道同 uid），不会新建。
         */
        return new ResolvedIdentity(Provider.PHONE, uid, "PHONE:" + uid,
                phone, req.nickname(), req.avatar());
    }

}
