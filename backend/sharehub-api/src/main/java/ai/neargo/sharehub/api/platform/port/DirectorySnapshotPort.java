package ai.neargo.sharehub.api.platform.port;

import java.util.List;

/**
 * 人员目录快照（M5 目录同步用）：在职员工 + 启用的代理账户。
 *
 * <p>编号口径<b>必须与令牌内省的 {@code sub} 一致</b>，否则同一个人在 OpsKit 里会变成两个成员（同步建一个、登录 JIT 再建一个）：
 * 员工 = {@code iam_employee.employee_no}（员工登录名即工号）；代理 = {@code agt_principal.principal_no}（人，不是账户 ——
 * 一个人可在多个代理下有账户，登录时按人发 sub）。
 */
public interface DirectorySnapshotPort {

    record Staff(String employeeNo, String name, String roleNo) {
    }

    /** 一个代理账户 = 某人在某代理下的一份成员关系 */
    record AgentAccount(String principalNo, String agentNo, String agentName, String displayName, boolean primary) {
    }

    record Snapshot(List<Staff> staff, List<AgentAccount> agentAccounts) {
    }

    Snapshot snapshot();
}
