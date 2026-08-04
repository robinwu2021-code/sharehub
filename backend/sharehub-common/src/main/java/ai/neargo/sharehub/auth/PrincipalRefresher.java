package ai.neargo.sharehub.auth;

import java.util.List;

/**
 * 会话主体重建（口径 B 用）。**基础设施侧的抽象**：`StaffTokenAuthFilter` 依赖本接口而非 IAM 业务
 * `PermissionService`，从而 infra 层（`auth`）不反向依赖 IAM 业务模块（`platform.iam`）——
 * 依赖倒置，保证 [ADR-015](../../../../../../../../docs/technical/ADR/ADR-015-权限模块化与打包方式.md) 的分层：
 * infra ← iam ← 业务。由 IAM 模块的 {@code PermissionService} 实现并注入。
 */
public interface PrincipalRefresher {

    /** 按会话角色重载权限/数据范围，返回刷新后的主体。 */
    LoginUser rebuild(LoginUser current, List<String> roleNos);
}
