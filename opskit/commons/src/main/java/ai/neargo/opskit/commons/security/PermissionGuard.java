package ai.neargo.opskit.commons.security;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;

/**
 * 用例里的产品权限判定（各模块共用）。{@code @RequiresPermission} 是声明，这里是执行。
 * 应用自身（不代成员）视为账户级受信系统：空间授权已在认证时校验，这里不再判产品权限。
 */
public final class PermissionGuard {

    private PermissionGuard() {
    }

    public static void require(Actor actor, String permissionCode) {
        if (actor.isAppItself() || actor.has(permissionCode)) {
            return;
        }
        throw new OpsKitException(CommonErrors.PERMISSION_DENIED, "missing permission " + permissionCode);
    }
}
