package ai.neargo.powerbank.auth;

import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.common.security.rbac.PermissionCarrier;
import ai.neargo.common.security.rbac.Permissions;

import java.util.List;
import java.util.Set;

/**
 * 统一登录主体（运营端 STAFF/AGENT + C 端 CONSUMER 共用）。实现 neargo {@link PermissionCarrier}，
 * 使 neargo 的 {@code @perm}（PermChecker）直接读其权限码判定。
 * <p>业务层与权限层**只经 {@link SecurityUtils} 读取它**，不直接碰 {@code SecurityContextHolder}（业务/权限分离）。
 *
 * @param realm     身份域
 * @param userNo    业务用户号（运营端=username/employee_no；C 端=c_user_no）
 * @param username  展示名
 * @param role      运营端角色码；C 端固定 "CONSUMER"
 * @param perms     权限码（运营端 RBAC；C 端为空）
 * @param tenantId  租户（MVP 恒 MAIN）
 * @param agentNo   代理号（AGENT 才有）
 * @param dataScope 数据范围（运营端；C 端为 SELF）
 */
public record LoginUser(
        Realm realm,
        String userNo,
        String username,
        String role,
        List<String> perms,
        String tenantId,
        String agentNo,
        DataScopeSpec dataScope) implements PermissionCarrier {

    public boolean isConsumer() {
        return realm == Realm.CONSUMER;
    }

    /** neargo {@link PermissionCarrier}：供 {@code @perm} 读取。 */
    @Override
    public Set<String> grantedPermissions() {
        return perms == null ? Set.of() : Set.copyOf(perms);
    }

    /** 权限码通配判定（委托 neargo {@link Permissions#matches}，前后端语义一致）。 */
    public boolean hasPerm(String code) {
        return Permissions.matches(perms, code);
    }
}
