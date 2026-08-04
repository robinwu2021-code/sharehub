package ai.neargo.sharehub.agent.ext.service.impl;

import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AgentAccount;
import ai.neargo.sharehub.agent.ext.entity.AgtAccount;
import ai.neargo.sharehub.agent.ext.mapper.AgtAccountMapper;
import ai.neargo.sharehub.agent.ext.service.AgentAccountService;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import org.springframework.stereotype.Service;

/**
 * 代理账号实现。
 *
 * <p><b>不写 {@code dataScope}</b>：它的落点是 {@code iam_data_scope}
 * （{@code subject_type='AGENT_ACCOUNT'}，[db-design §1.7]），不是本表的列。
 */
@Service
public class AgentAccountServiceImpl extends AbstractCrudService<AgtAccount, AgentAccount>
        implements AgentAccountService {

    public AgentAccountServiceImpl(AgtAccountMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "account_no";
    }

    @Override
    protected String keyOf(AgtAccount e) {
        return e.getAccountNo();
    }

    @Override
    protected void setKey(AgtAccount e, String no) {
        e.setAccountNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.AGENT_ACCOUNT;
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"account_no", "agent_name", "username", "login_phone"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"agentNo", "status"};
    }

    @Override
    protected void beforeCreate(AgtAccount e) {
        if (e.getAgentNo() == null || e.getAgentNo().isBlank()) {
            throw new IllegalArgumentException("代理账号必须归属某个代理商: agentNo 必填");
        }
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus("ACTIVE");
    }

    @Override
    protected void beforeUpdate(AgtAccount e, AgtAccount current) {
        // 归属代理是账号身份的一部分，改了等于换了个人 —— 路径改归属会绕开划拨审批，禁止。
        e.setAgentNo(current.getAgentNo());
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus(current.getStatus());
        // 凭据引用由 pb_auth 侧维护，业务面不可直改。
        e.setCredRef(current.getCredRef());
    }

    @Override
    protected AgentAccount toVO(AgtAccount e) {
        // TODO(iam): dataScope 取自 iam_data_scope(subject_type='AGENT_ACCOUNT', subject_no=accountNo)
        //   的 scope_type —— iam 域 mapper 不在本分片边界内，落地后在此注入查询，勿改回本表加列。
        return new AgentAccount(e.getAccountNo(), e.getAgentNo(), e.getAgentName(),
                e.getUsername(), e.getLoginPhone(), e.getStatus(), null,
                e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
    }
}
