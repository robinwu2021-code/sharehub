package ai.neargo.opskit.commons.tenancy;

import java.util.Objects;

/** 一次调用所在的账户与空间。运行时数据一律要求具体空间；只有定义解析会用到模板空间。 */
public record TenantContext(AccountId accountId, WorkspaceId workspaceId) {
    public TenantContext {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(workspaceId, "workspaceId");
    }
}
