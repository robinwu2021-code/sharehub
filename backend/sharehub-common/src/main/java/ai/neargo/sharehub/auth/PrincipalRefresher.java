package ai.neargo.sharehub.auth;

import java.util.List;

/**
 * 会话主体重建（口径 B 用）。**基础设施侧的抽象**：`StaffTokenAuthFilter` 依赖本接口而非 IAM 业务
 * `PermissionService`，从而 infra 层（`auth`）不反向依赖 IAM 业务模块（`platform.iam`）——
 * 依赖倒置，保证 [ADR-015](../../../../../../../../docs/technical/ADR/ADR-015-权限模块化与打包方式.md) 的分层：
 * infra ← iam ← 业务。由 IAM 模块的 {@code PermissionService} 实现并注入。
 */
public interface PrincipalRefresher {

    /**
     * 按会话角色重载权限/数据范围，返回刷新后的主体。
     *
     * @return 刷新后的主体；<b>{@code null} = 该主体已失效</b>（员工离职 / 停用），
     *         调用方应作废这个会话。
     *
     *         <p>这个出口是 2026-09-25 补的，补之前有个洞：本方法按**会话里带来的**
     *         {@code roleNos} 重算权限，<b>全程不读 {@code iam_employee}</b> ——
     *         于是停用一个员工再 {@link PermVersion#bump()}，他的会话只会「照原样
     *         重算一遍」然后放行。而 {@code TokenStore} 只有 {@code revoke(token)}、
     *         {@code sys_token.subject_no} 没有索引、Redis 也没有二级索引，
     *         「撤销某人的全部会话」当时根本做不到。
     *         让重建回查在职状态，是不建任何索引就能堵上它的做法。
     */
    LoginUser rebuild(LoginUser current, List<String> roleNos);
}
