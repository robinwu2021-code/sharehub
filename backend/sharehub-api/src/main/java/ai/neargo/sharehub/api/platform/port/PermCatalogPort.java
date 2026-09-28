package ai.neargo.sharehub.api.platform.port;

import java.util.Set;

/**
 * 权限码目录（{@code iam_permission}）只读口。
 *
 * <p>用处：把角色上的<b>通配码</b>（{@code finance:*}、{@code report:*:read}）展开成具体码，交给<b>只认具体码</b>的外部系统 ——
 * OpsKit 按内省应答的 {@code perms} 精确匹配审批候选人（{@code perm:finance:withdrawal:audit}），
 * 不展开的话持 {@code finance:*} 的财务永远不是候选人，审批单没人能审（M5，2026-09-28）。
 */
public interface PermCatalogPort {

    /** 目录里全部具体码 */
    Set<String> allCodes();
}
