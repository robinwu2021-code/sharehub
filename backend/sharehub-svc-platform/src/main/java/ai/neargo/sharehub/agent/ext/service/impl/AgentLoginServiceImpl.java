package ai.neargo.sharehub.agent.ext.service.impl;

import ai.neargo.sharehub.agent.apply.service.OtpGate;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.agent.ext.AccountStatus;
import ai.neargo.sharehub.agent.ext.entity.AgtAccount;
import ai.neargo.sharehub.agent.ext.entity.AgtPrincipal;
import ai.neargo.sharehub.agent.ext.mapper.AgtAccountMapper;
import ai.neargo.sharehub.agent.ext.mapper.AgtPrincipalMapper;
import ai.neargo.sharehub.platform.iam.port.AgentIdentityPort;
import ai.neargo.sharehub.platform.iam.port.AgentIdentityPort.OperatorMembership;
import ai.neargo.sharehub.identity.IdentifierHasher;
import ai.neargo.sharehub.identity.IdentifierNormalizer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 代理端实名登录实现。设计理由（含「为什么接口在 platform」）见 {@link AgentIdentityPort} 的类注释。
 */
@lombok.extern.slf4j.Slf4j
@Service
public class AgentLoginServiceImpl implements AgentIdentityPort {

    /**
     * 统一的失败文案。**「号不存在」和「码不对」必须说同一句话** ——
     * 一旦分开，这个接口就能被用来批量判断某个手机号是不是本平台代理商。
     */
    private static final String LOGIN_FAILED = "账号或验证码不正确";

    private final AgtPrincipalMapper principalMapper;
    private final AgtAccountMapper accountMapper;
    private final IdentifierNormalizer normalizer;
    private final IdentifierHasher hasher;
    private final OtpGate otpGate;

    public AgentLoginServiceImpl(AgtPrincipalMapper principalMapper, AgtAccountMapper accountMapper,
                                 IdentifierNormalizer normalizer, IdentifierHasher hasher,
                                 OtpGate otpGate) {
        this.principalMapper = principalMapper;
        this.accountMapper = accountMapper;
        this.normalizer = normalizer;
        this.hasher = hasher;
        this.otpGate = otpGate;
    }

    /**
     * 发登录验证码。标识是**一个**字段：含 {@code @} 走邮箱（主登录方式，R1），否则走手机号（R8）。
     *
     * <p>手机号只认**已验证**的（R5）：运营录入的手机号可能录错，未验证就能收码登录，
     * 等于把账号交给号码的真实主人 —— 那个人不是商家。
     */
    @Override
    public String sendLoginOtp(String identifier) {
        Target t = resolve(identifier);
        /*
         * 查无此人 → 静默返回，**不抛错**。
         * 抛错的话，调用方从「报错 vs 成功」就能读出这个号在不在库里 ——
         * 批量跑一遍就是一份商家邮箱 / 手机号名单。宁可让输错号的人等一条永远不到的码。
         */
        if (t == null) return null;
        return t.email() != null ? otpGate.issueEmail(t.email()) : otpGate.issue(t.phone());
    }

    @Override
    public String verifyLoginOtp(String identifier, String otp) {
        if (otp == null || otp.isBlank()) {
            throw new IllegalArgumentException(LOGIN_FAILED);
        }
        Target t = resolve(identifier);
        if (t == null) {
            /*
             * 即使人不存在也**不能直接返回别的话** —— 要和真实路径的失败说同一句，
             * 否则「号不存在 vs 码不对」两句话本身就能区分出号存不存在。
             * OTP 本身的节流由 OtpGate 负责。
             */
            throw new IllegalArgumentException(LOGIN_FAILED);
        }
        try {
            if (t.email() != null) otpGate.verifyEmail(t.email(), otp);
            else otpGate.verify(t.phone(), otp);
        } catch (IllegalArgumentException | BizException e) {
            /*
             * **必须同时接 BizException**：2026-09-25 的 i18n 批次把 OtpService 的
             * IllegalArgumentException 换成了 BizException，而这个 catch 只接前者 ——
             * 于是「码不对」直接以 `error.otp.invalid`（「验证码错误或已过期」）冒出去，
             * 而「号不存在」仍回 LOGIN_FAILED。**两句话不一样，账号枚举防护当场失效**。
             *
             * 这正是「改了被 catch 的异常类型，catch 静默失配」那一类：不报错、不编译失败，
             * 只是那道防护没了。`AgentLoginFlowTest` 从那天起一直红着。
             * 往后要改 OtpGate 的抛出类型，先看这里。
             */
            throw new IllegalArgumentException(LOGIN_FAILED);
        }
        AgtPrincipal p = t.principal();
        // 首登即验证（R4）：能收到这封邮件的人就是这个邮箱的主人。之后这个邮箱才占唯一。
        if (t.email() != null && !Integer.valueOf(1).equals(p.getEmailVerified())) {
            p.setEmailVerified(1);
            principalMapper.updateById(p);
        }
        return p.getPrincipalNo();
    }

    /** 一次登录要找的人：邮箱登录时 {@code email} 非空，手机号登录时 {@code phone} 非空。 */
    private record Target(AgtPrincipal principal, String email, String phone) {
    }

    /** 标识 → 可登录的自然人；查不到、停用、格式不对一律 null（不区分，见上）。 */
    private Target resolve(String identifier) {
        if (identifier == null || identifier.isBlank()) return null;
        try {
            if (IdentifierNormalizer.looksLikeEmail(identifier)) {
                String email = IdentifierNormalizer.email(identifier);
                AgtPrincipal p = findByEmail(email);
                return active(p) ? new Target(p, email, null) : null;
            }
            String phone = normalizer.phone(identifier);
            AgtPrincipal p = principalMapper.selectOne(new LambdaQueryWrapper<AgtPrincipal>()
                    .eq(AgtPrincipal::getPhoneUk, hasher.hash(phone)).last("limit 1"));
            return active(p) ? new Target(p, null, phone) : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /**
     * 邮箱 → 自然人。已验证的那一个优先（唯一）；没有已验证的，取最早建的那个未验证行。
     *
     * <p>同一邮箱出现多个未验证自然人，只可能是运营在不同商家上录了同一个邮箱 —— 而开通时按邮箱命中已有的人会复用
     * （需求方案 §五「开通产物」），所以正常路径下不会有第二行。真出现时取最早的，并留 WARN 让运营核对。
     */
    private AgtPrincipal findByEmail(String normalizedEmail) {
        String h = hasher.hash(normalizedEmail);
        AgtPrincipal verified = principalMapper.selectOne(new LambdaQueryWrapper<AgtPrincipal>()
                .eq(AgtPrincipal::getEmailUk, h).last("limit 1"));
        if (verified != null) return verified;
        List<AgtPrincipal> rows = principalMapper.selectList(new LambdaQueryWrapper<AgtPrincipal>()
                .eq(AgtPrincipal::getEmailHash, h).orderByAsc(AgtPrincipal::getId).last("limit 2"));
        if (rows.size() > 1) {
            log.warn("同一未验证邮箱挂在多个自然人上，登录取最早的 principalNo={}；请运营在商家档案核对邮箱是否录错",
                    rows.get(0).getPrincipalNo());
        }
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static boolean active(AgtPrincipal p) {
        return p != null && AccountStatus.ACTIVE.name().equals(p.getStatus());
    }

    @Override
    public List<OperatorMembership> memberships(String principalNo) {
        if (principalNo == null || principalNo.isBlank()) {
            return List.of();
        }
        // 走 idx_agt_account_pr (principal_no, is_primary)：默认主体排在前面
        List<AgtAccount> rows = accountMapper.selectList(new LambdaQueryWrapper<AgtAccount>()
                .eq(AgtAccount::getPrincipalNo, principalNo)
                .eq(AgtAccount::getStatus, AccountStatus.ACTIVE.name())
                .orderByDesc(AgtAccount::getIsPrimary)
                .orderByAsc(AgtAccount::getAccountNo));
        return rows.stream().map(AgentLoginServiceImpl::toVO).toList();
    }

    @Override
    public OperatorMembership primaryOf(String principalNo) {
        List<OperatorMembership> all = memberships(principalNo);
        if (all.isEmpty()) {
            /*
             * 一个主体都没有：入驻还没通过，或名下主体全被停用。
             * **不发 token** —— 发了也只是一个什么都看不到的空壳会话，
             * 用户会以为「登录成功但系统坏了」，比明确告诉他「还没通过审核」糟得多。
             */
            throw new IllegalStateException("你名下还没有可用的运营主体：入驻审核尚未通过，或主体已被停用");
        }
        return all.get(0);   // memberships 已按 is_primary 降序
    }

    @Override
    public OperatorMembership requireMembership(String principalNo, String agentNo) {
        return memberships(principalNo).stream()
                .filter(m -> m.agentNo().equals(agentNo))
                .findFirst()
                // 不说「该主体不存在」，说「你不属于它」—— 前者会泄露别家主体编号的存在性
                .orElseThrow(() -> new IllegalArgumentException("你不属于该运营主体，无法切换"));
    }

    private static OperatorMembership toVO(AgtAccount a) {
        return new OperatorMembership(a.getAgentNo(), a.getAgentName(), a.getAccountNo(),
                a.getDisplayName(), eq1(a.getIsOwner()), eq1(a.getIsPrimary()), a.getStatus());
    }

    private static Boolean eq1(Integer v) {
        return v != null && v == 1;
    }
}
