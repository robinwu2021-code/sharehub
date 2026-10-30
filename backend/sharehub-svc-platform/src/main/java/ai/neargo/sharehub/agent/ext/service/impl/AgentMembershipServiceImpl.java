package ai.neargo.sharehub.agent.ext.service.impl;

import ai.neargo.sharehub.agent.entity.AgtAgent;
import ai.neargo.sharehub.agent.ext.AccountStatus;
import ai.neargo.sharehub.agent.ext.entity.AgtAccount;
import ai.neargo.sharehub.agent.ext.entity.AgtPrincipal;
import ai.neargo.sharehub.agent.ext.mapper.AgtAccountMapper;
import ai.neargo.sharehub.agent.ext.mapper.AgtPrincipalMapper;
import ai.neargo.sharehub.agent.ext.service.AgentMembershipService;
import ai.neargo.sharehub.agent.mapper.AgentMapper;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.common.crud.BizNoSequence;
import ai.neargo.sharehub.identity.IdentifierHasher;
import ai.neargo.sharehub.identity.IdentifierNormalizer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link AgentMembershipService} 的实现 —— 与入驻审核那条路共用同一套规则。 */
@Service
public class AgentMembershipServiceImpl implements AgentMembershipService {

    private static final Logger log = LoggerFactory.getLogger(AgentMembershipServiceImpl.class);

    /** 取号要绕开逻辑删除过滤，只能走原生 SQL —— 见 BizNoSequence 的类注释。 */
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    private final AgentMapper agents;
    private final AgtPrincipalMapper principals;
    private final AgtAccountMapper accounts;
    private final IdentifierNormalizer normalizer;
    private final IdentifierHasher hasher;

    public AgentMembershipServiceImpl(AgentMapper agents, AgtPrincipalMapper principals,
                                      AgtAccountMapper accounts, IdentifierNormalizer normalizer,
                                      IdentifierHasher hasher, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.agents = agents;
        this.principals = principals;
        this.accounts = accounts;
        this.normalizer = normalizer;
        this.hasher = hasher;
    }

    @Override
    @Transactional
    public String grantOwner(String agentNo, String rawPhone, String rawEmail, String displayName) {
        AgtAgent agent = agents.selectOne(new LambdaQueryWrapper<AgtAgent>()
                .eq(AgtAgent::getAgentNo, agentNo).last("limit 1"));
        if (agent == null) throw BizException.notFound(agentNo);
        /*
         * 不传就用档案上的联系手机/邮箱（V123 拆出来的那两列）——
         * 「开通登录」的常态就是「用他档案上那个号」，让调用方再抄一遍只会抄错。
         */
        String rp = rawPhone != null && !rawPhone.isBlank() ? rawPhone : agent.getPhone();
        String re = rawEmail != null && !rawEmail.isBlank() ? rawEmail : agent.getEmail();
        if (rp == null || rp.isBlank()) {
            throw BizException.badRequest("error.agent.login_phone_required", agentNo);
        }
        /*
         * **邮箱同样必填**，不是可选项：`agt_principal.email_hash` 是 NOT NULL，且 V168 起是主登录标识。
         * 这与入驻审核那条路一致（自助申请手机号与邮箱两者都必填）——
         * 自然人有两个登录标识，缺一个这行就建不出来。
         * 不在这里放宽成可空：放宽了会让「建得出但只能用手机号登」的自然人混进来，
         * 而邮箱那条登录路径查不到它。
         */
        if (re == null || re.isBlank()) {
            throw BizException.badRequest("error.agent.login_email_required", agentNo);
        }

        String phone = normalizer.phone(rp);
        String email = IdentifierNormalizer.email(re);
        String phoneHash = hasher.hash(phone);

        /*
         * ① 自然人：按邮箱查，**命中则复用**（同一个人服务多家主体是常态，ADR-030）。
         *    V168 起邮箱是主标识，与入驻审核、登录同一口径：已验证的优先，否则取最早的未验证行 ——
         *    三处找人口径不一致，就会「开通挂在 A 上、登录进的是 B」。
         *    复用时**绝不动 cred_ref** —— 一个人一套密码，改密一处全主体生效；
         *    在这里顺手重置它，等于把他在别家主体的登录也改了。
         */
        String emailHash = hasher.hash(email);
        AgtPrincipal principal = principals.selectOne(new LambdaQueryWrapper<AgtPrincipal>()
                .eq(AgtPrincipal::getEmailUk, emailHash).last("limit 1"));
        if (principal == null) {
            principal = principals.selectOne(new LambdaQueryWrapper<AgtPrincipal>()
                    .eq(AgtPrincipal::getEmailHash, emailHash).orderByAsc(AgtPrincipal::getId).last("limit 1"));
        }
        boolean firstOperatorOfThisPerson = principal == null;
        if (principal == null) {
            principal = new AgtPrincipal();
            principal.setPrincipalNo(BizNoSequence.next(jdbc, "agt_principal", "principal_no", "PR", 4));
            principal.setPhoneHash(phoneHash);
            principal.setPhoneMask(IdentifierNormalizer.mask(phone));
            // 运营录入的标识一律未验证（V168 / R3）：邮箱在首登收码时验证，手机号由本人登录后验证
            principal.setPhoneVerified(0);
            principal.setEmailVerified(0);
            principal.setEmailHash(emailHash);
            principal.setEmailMask(IdentifierNormalizer.maskEmail(email));
            principal.setHashVer(hasher.version());
            principal.setStatus(AccountStatus.ACTIVE.name());
            principal.setTenantId("MAIN");
            principals.insert(principal);
        }

        /*
         * ② 成员关系。**幂等**：已经是这家主体的成员就直接返回 ——
         *    重复点「开通登录」不该建出第二行（库上 uk_agt_account_member 也会拦，
         *    但撞唯一键是 500，而这是个正常的重复操作）。
         */
        AgtAccount exist = accounts.selectOne(new LambdaQueryWrapper<AgtAccount>()
                .eq(AgtAccount::getAgentNo, agentNo)
                .eq(AgtAccount::getPrincipalNo, principal.getPrincipalNo()).last("limit 1"));
        if (exist != null) {
            log.info("代理登录已开通，跳过 agentNo={} principalNo={}", agentNo, principal.getPrincipalNo());
            return exist.getAccountNo();
        }

        AgtAccount acc = new AgtAccount();
        acc.setAccountNo(BizNoSequence.next(jdbc, "agt_account", "account_no", "AA", 4));
        acc.setAgentNo(agentNo);
        acc.setAgentName(agent.getName());
        acc.setUsername(principal.getPrincipalNo());   // username 已不是唯一键，留兼容（ADR-030 §七 3）
        acc.setPrincipalNo(principal.getPrincipalNo());
        acc.setIsOwner(1);
        /*
         * is_primary 只给这个人的**第一个**主体（ADR-030 §2.5 约束 2）——
         * 同一人两个 is_primary=1 的话，登录后进哪个主体是不确定的。
         */
        acc.setIsPrimary(firstOperatorOfThisPerson ? 1 : 0);
        String dn = displayName != null && !displayName.isBlank() ? displayName
                : (agent.getContactName() != null && !agent.getContactName().isBlank()
                        ? agent.getContactName() : agent.getName());
        acc.setDisplayName(dn);
        acc.setStatus(AccountStatus.ACTIVE.name());
        acc.setTenantId("MAIN");
        accounts.insert(acc);

        // 只记"给谁开的"，**不记手机号**（日志的读者远多于接口的调用者）
        log.info("代理登录已开通 agentNo={} principalNo={} accountNo={} 首个主体={}",
                agentNo, principal.getPrincipalNo(), acc.getAccountNo(), firstOperatorOfThisPerson);
        return acc.getAccountNo();
    }
}
