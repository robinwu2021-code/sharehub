package ai.neargo.sharehub.agent.apply.service;

import ai.neargo.sharehub.agent.apply.entity.AgtApply;
import ai.neargo.sharehub.agent.apply.mapper.AgtApplyMapper;
import ai.neargo.sharehub.agent.ext.entity.AgtAccount;
import ai.neargo.sharehub.agent.ext.mapper.AgtAccountMapper;
import ai.neargo.sharehub.api.platform.port.NotifyPort;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.platform.cred.service.CredentialService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商家开通邀请（商家一期 B2；用户 2026-10-28：保留四眼，代录的商家给初始密码，登录后修改）。
 *
 * <p>两件事一起做：给属主发一次性初始口令（{@code realm=AGENT}，首登强制改），并把登录邮箱与口令
 * 发到申请单上的联系邮箱。<b>口令只出现两次</b>：这次接口响应（给运营当面转告）与这封邮件 ——
 * 不落日志、不可再查；丢了就重发邀请，重发会作废上一个口令。
 *
 * <p>同一个人开第二家商家时<b>不重置</b>他的口令（一个人一套口令，改一处全主体生效），
 * 只发一封「你名下多了一家商家」的通知。
 */
@Slf4j
@Component
public class MerchantInvite {

    static final String REALM = "AGENT";

    private final CredentialService credentials;
    private final NotifyPort notify;
    private final AgtAccountMapper accounts;
    private final AgtApplyMapper applies;
    private final String loginUrl;

    public MerchantInvite(CredentialService credentials, NotifyPort notify, AgtAccountMapper accounts,
                          AgtApplyMapper applies,
                          @Value("${sharehub.merchant.login-url:}") String loginUrl) {
        this.credentials = credentials;
        this.notify = notify;
        this.accounts = accounts;
        this.applies = applies;
        this.loginUrl = loginUrl;
    }

    /**
     * 开通后调用：此人还没有口令就发初始口令并寄邀请；已有口令只寄通知。
     *
     * @return 新发的一次性口令；此人已有口令时为 null
     */
    public String onActivated(AgtApply apply, String agentNo, String principalNo) {
        String temp = credentials.exists(REALM, principalNo) ? null : credentials.resetPassword(REALM, principalNo);
        send(apply.getContactEmail(), apply.getOperatorName(), temp);
        return temp;
    }

    /**
     * 重发邀请：作废旧口令、发新的一次性口令（首登强制改），再寄一封邀请。
     * 用在商家说「没收到邮件 / 口令忘了还没改」的时候。
     */
    @Transactional
    public String resend(String agentNo) {
        AgtAccount owner = accounts.selectOne(new LambdaQueryWrapper<AgtAccount>()
                .eq(AgtAccount::getAgentNo, agentNo).eq(AgtAccount::getIsOwner, 1).last("limit 1"));
        if (owner == null || owner.getPrincipalNo() == null) throw BizException.notFound(agentNo);
        AgtApply apply = applies.selectOne(new QueryWrapper<AgtApply>()
                .eq("operator_no", agentNo).eq("deleted", 0).orderByDesc("id").last("limit 1"));
        String temp = credentials.resetPassword(REALM, owner.getPrincipalNo());
        send(apply == null ? null : apply.getContactEmail(), owner.getAgentName(), temp);
        log.info("重发商家邀请 agentNo={}", agentNo);
        return temp;
    }

    private void send(String email, String merchantName, String temp) {
        if (email == null || email.isBlank()) {
            // 没有联系邮箱：口令只能靠接口响应当面转告。运营该在商家档案补邮箱
            log.warn("商家开通邀请未发出：申请单没有联系邮箱 merchant={}，请运营补录邮箱后重发邀请", merchantName);
            return;
        }
        StringBuilder body = new StringBuilder()
                .append("Your ShareHub merchant account \"").append(merchantName).append("\" is ready.\n")
                .append("Sign in with this email address");
        if (temp != null) {
            body.append(" and the one-time password: ").append(temp)
                .append("\nYou will be asked to set your own password after the first sign-in.");
        } else {
            body.append(" using your existing password or an email code.");
        }
        if (!loginUrl.isBlank()) body.append("\n").append(loginUrl);
        NotifyPort.Delivery d = notify.sendToTarget(email, "EMAIL", "MERCHANT_INVITE", body.toString());
        if (!d.sent()) {
            // 不打邮箱与口令（日志规范）；运营动作：查 notify_log 的 MERCHANT_INVITE，或当面转告后让商家改密
            log.warn("商家开通邀请投递失败 merchant={} reason={}，口令已在接口响应里返回给运营", merchantName, d.reason());
        }
    }
}
