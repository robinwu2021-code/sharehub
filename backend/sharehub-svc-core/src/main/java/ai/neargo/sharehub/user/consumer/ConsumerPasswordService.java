package ai.neargo.sharehub.user.consumer;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.identity.IdentifierHasher;
import ai.neargo.sharehub.identity.IdentifierNormalizer;
import ai.neargo.sharehub.user.entity.UsrIdentity;
import ai.neargo.sharehub.user.mapper.UserMappers.UsrIdentityMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

/**
 * C 端口令的三个动作：注册时设、忘记密码经 OTP 重设、本人改密。
 *
 * <p>单独成类而不塞进 {@link ConsumerAuthService}：那个类的职责是「解析身份 → 建户/归并 → 签发」，
 * 口令是另一件事。而「按手机号找 cUserNo」这段两边都要用，收在这里一份
 * （{@link PasswordStrategy} 也调它），免得归一/派生的口径在两处分叉 —— 那正是
 * {@link IdentifierHasher} 的 javadoc 反复警告的失败形态：注册能成、登录查不到，不报任何错。
 *
 * <p><b>开关门禁在这里也要判</b>：密码登录关着时，「设口令 / 重设 / 改密」都该拒。
 * 只在登录那条路上判的话，用户能设一个永远用不上的口令，
 * 而「设置成功」这句提示会让他以为下次能用它登录。
 */
@Service
public class ConsumerPasswordService {

    /** 与 {@link PasswordStrategy} 共用同一句：不暴露「号存不存在」。 */
    private static final String LOGIN_FAILED = "error.auth.login_failed";

    private final ConsumerCredentialGate credentials;
    private final LoginMethodGate methodGate;
    private final OtpService otp;
    private final UsrIdentityMapper identityMapper;
    private final IdentifierNormalizer normalizer;
    private final IdentifierHasher hasher;

    public ConsumerPasswordService(ConsumerCredentialGate credentials, LoginMethodGate methodGate,
                                   OtpService otp, UsrIdentityMapper identityMapper,
                                   IdentifierNormalizer normalizer, IdentifierHasher hasher) {
        this.credentials = credentials;
        this.methodGate = methodGate;
        this.otp = otp;
        this.identityMapper = identityMapper;
        this.normalizer = normalizer;
        this.hasher = hasher;
    }

    /**
     * 注册时顺带设口令。{@code rawPassword} 为空就什么都不做 —— 注册**不强制**设口令
     * （OTP 登录本身就是完整的登录方式，逼用户想一个口令只会让他想一个弱的）。
     *
     * @param country ISO alpha-2，判开关用
     */
    public void setOnRegister(String cUserNo, String rawPassword, String country) {
        if (rawPassword == null || rawPassword.isBlank()) return;
        requirePasswordEnabled(country);
        credentials.setPassword(cUserNo, rawPassword);
    }

    /**
     * 忘记密码：**先验 OTP，再设口令**。顺序不能反 —— 反了就是任何人都能改任意号的口令。
     *
     * <p>号不存在时也走完 OTP 校验才拒，理由同 {@link PasswordStrategy}：
     * 「秒失败 vs 验完码再失败」同样能区分出号存不存在。
     */
    public void resetByOtp(String rawPhone, String code, String newPassword, String country) {
        requirePasswordEnabled(country);
        String phone = normalizer.phone(rawPhone);   // 与发码时同一个方法，否则「收到码了但验不过」
        otp.verify(phone, code);                     // 不通过直接抛，下面一行都到不了
        String cUserNo = findCUserNo(phone, rawPhone);
        if (cUserNo == null) throw BizException.badRequest(LOGIN_FAILED);
        credentials.setPassword(cUserNo, newPassword);
    }

    /** 本人改密（已登录态，cUserNo 来自会话不是请求体）。 */
    public void change(String cUserNo, String oldPassword, String newPassword, String country) {
        requirePasswordEnabled(country);
        credentials.changePassword(cUserNo, oldPassword, newPassword);
    }

    /** 按手机号找 {@code cUserNo}：先新算法 uid，再旧算法（惰性重键的存量账号也要能用口令）。 */
    String findCUserNo(String normalizedPhone, String rawPhone) {
        String uid = hasher.hash(normalizedPhone);
        String legacy = PhoneOtpStrategy.legacyUid(rawPhone);
        return DataScopeContext.executeWithoutScope(() -> {
            UsrIdentity hit = byUid(uid);
            if (hit == null) hit = byUid(legacy);
            return hit == null ? null : hit.getCUserNo();
        });
    }

    private UsrIdentity byUid(String uid) {
        if (uid == null) return null;
        return identityMapper.selectOne(new LambdaQueryWrapper<UsrIdentity>()
                .eq(UsrIdentity::getProvider, Provider.PHONE.name())
                .eq(UsrIdentity::getProviderUid, uid).last("limit 1"));
    }

    private void requirePasswordEnabled(String country) {
        if (!methodGate.enabled(country, "password")) {
            throw BizException.badRequest("error.auth.grant_type_disabled", "password");
        }
    }
}
