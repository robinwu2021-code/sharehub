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
        // dataScope 恒 null，**原因不是原先写的那个**。
        //
        // 原注释说「iam 域 mapper 不在本分片边界内」—— 不成立：DataScopeService 就在本模块
        // （sharehub-svc-platform 的 platform.org），注进来即可，不需要 port。
        //
        // 真正的阻塞是词表：DataScopeSubject 只有 ROLE / EMPLOYEE，**没有 AGENT_ACCOUNT**，
        // 所以 iam_data_scope 里根本不会有这个主体的行，save() 传它还会抛「非法 subjectType」。
        // 也就是说这一块整体没有实现，不是这里漏了一次查询 —— 照原注释去补 port 会白做。
        //
        // 要不要实现取决于一个产品问题：代理账号能不能看得比它所属的代理更窄
        // （代理会话本来就按 agentNo 收口）。见 docs/requirements/待裁决清单-9-25.md。
        // 运营端已把那个选不动的「数据范围」表单项撤掉，列显示「未设置」。
        return new AgentAccount(e.getAccountNo(), e.getAgentNo(), e.getAgentName(),
                e.getUsername(), e.getLoginPhone(), e.getStatus(), null,
                e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
    }
}
