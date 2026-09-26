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
@Service
public class AgentLoginServiceImpl implements AgentIdentityPort {

    /**
     * 统一的失败文案。**「号不存在」和「码不对」必须说同一句话** ——
     * 一旦分开，这个接口就能被用来批量判断某个手机号是不是本平台代理商。
     */
    private static final String LOGIN_FAILED = "手机号或验证码不正确";

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

    @Override
    public String sendLoginOtp(String rawPhone) {
        String phone = normalizer.phone(rawPhone);
        AgtPrincipal p = findByPhone(phone);
        /*
         * 查无此人 → 静默返回，**不抛错**。
         * 抛错的话，调用方从「报错 vs 成功」就能读出这个号在不在库里 ——
         * 批量跑一遍就是一份代理商手机号名单。宁可让输错号的人等一条永远不到的短信。
         */
        if (p == null || !AccountStatus.ACTIVE.name().equals(p.getStatus())) {
            return null;
        }
        return otpGate.issue(phone);
    }

    @Override
    public String verifyLoginOtp(String rawPhone, String otp) {
        if (otp == null || otp.isBlank()) {
            throw new IllegalArgumentException(LOGIN_FAILED);
        }
        String phone = normalizer.phone(rawPhone);
        AgtPrincipal p = findByPhone(phone);
        if (p == null || !AccountStatus.ACTIVE.name().equals(p.getStatus())) {
            /*
             * 即使人不存在也**不能直接返回** —— 要先让它走一遍和真实路径一样的失败，
             * 否则「秒失败 vs 验完码再失败」的耗时差同样能区分出号存不存在。
             * 这里直接抛同一句文案；OTP 本身的节流由 OtpGate 负责。
             */
            throw new IllegalArgumentException(LOGIN_FAILED);
        }
        try {
            otpGate.verify(phone, otp);
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
        return p.getPrincipalNo();
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

    private AgtPrincipal findByPhone(String normalizedPhone) {
        return principalMapper.selectOne(new LambdaQueryWrapper<AgtPrincipal>()
                .eq(AgtPrincipal::getPhoneHash, hasher.hash(normalizedPhone))
                .last("limit 1"));
    }

    private static OperatorMembership toVO(AgtAccount a) {
        return new OperatorMembership(a.getAgentNo(), a.getAgentName(), a.getAccountNo(),
                a.getDisplayName(), eq1(a.getIsOwner()), eq1(a.getIsPrimary()), a.getStatus());
    }

    private static Boolean eq1(Integer v) {
        return v != null && v == 1;
    }
}
