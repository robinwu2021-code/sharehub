package ai.neargo.sharehub.agent.port;

import ai.neargo.sharehub.agent.ext.AccountStatus;
import ai.neargo.sharehub.agent.ext.entity.AgtAccount;
import ai.neargo.sharehub.agent.ext.mapper.AgtAccountMapper;
import ai.neargo.sharehub.api.platform.port.DirectorySnapshotPort;
import ai.neargo.sharehub.platform.iam.EmployeeStatus;
import ai.neargo.sharehub.platform.org.entity.IamEmployee;
import ai.neargo.sharehub.platform.org.mapper.IamEmployeeMapper;
import ai.neargo.common.data.scope.DataScopeContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link DirectorySnapshotPort} 的本地实现。全量读（员工几十、代理账户几百的量级），
 * 且<b>不受数据范围约束</b>：这是给目录同步用的全局视图，不是某个操作人能看到的那一部分。
 *
 * <p>放在 agent 域而不是 platform 域：它要读代理账户，platform → agent 会与既有的 agent → loc → platform 成环
 * （ArchitectureTest#noCyclesBetweenDomains，2026-09-28 实测）；agent → platform 方向没有回路。
 */
@Component
public class LocalDirectorySnapshot implements DirectorySnapshotPort {

    private final IamEmployeeMapper employees;
    private final AgtAccountMapper accounts;

    public LocalDirectorySnapshot(IamEmployeeMapper employees, AgtAccountMapper accounts) {
        this.employees = employees;
        this.accounts = accounts;
    }

    @Override
    public Snapshot snapshot() {
        return DataScopeContext.executeWithoutScope(() -> {
            List<Staff> staff = employees.selectList(new LambdaQueryWrapper<IamEmployee>()
                            .eq(IamEmployee::getStatus, EmployeeStatus.ACTIVE.name()))
                    .stream().filter(e -> e.getEmployeeNo() != null && !e.getEmployeeNo().isBlank())
                    .map(e -> new Staff(e.getEmployeeNo(), e.getName(), e.getRoleNo())).toList();
            List<AgentAccount> agents = accounts.selectList(new LambdaQueryWrapper<AgtAccount>()
                            .eq(AgtAccount::getStatus, AccountStatus.ACTIVE.name())
                            .isNotNull(AgtAccount::getPrincipalNo))
                    .stream().map(a -> new AgentAccount(a.getPrincipalNo(), a.getAgentNo(), a.getAgentName(),
                            a.getDisplayName() == null || a.getDisplayName().isBlank() ? a.getAgentName() : a.getDisplayName(),
                            a.getIsPrimary() != null && a.getIsPrimary() == 1)).toList();
            return new Snapshot(staff, agents);
        });
    }
}
