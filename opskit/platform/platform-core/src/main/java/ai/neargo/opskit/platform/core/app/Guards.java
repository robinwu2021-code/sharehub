package ai.neargo.opskit.platform.core.app;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.platform.api.Permission;

/**
 * 用例里的产品权限判定。{@code @RequiresPermission} 是声明（给架构测试 A12 与文档看），这里是执行 ——
 * 两者写在同一个方法里，改一个时另一个就在眼前。
 *
 * <p>应用自身（不代成员）视为账户级受信系统：在它被授权的空间里不做产品权限判定，空间授权已在认证时校验。
 */
public final class Guards {

    private Guards() {
    }

    public static void require(Actor actor, Permission permission) {
        ai.neargo.opskit.commons.security.PermissionGuard.require(actor, permission.code());
    }
}
