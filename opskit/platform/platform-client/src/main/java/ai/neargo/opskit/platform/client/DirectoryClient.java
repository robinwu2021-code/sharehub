package ai.neargo.opskit.platform.client;

import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.platform.api.directory.GroupView;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 目录能力的客户端（上层模块经它读组与成员，不碰平台 core）。 */
public interface DirectoryClient {

    Optional<GroupView> findGroup(TenantContext t, String groupCode);

    /** 组的当前成员 ID。 */
    List<String> membersOf(TenantContext t, String groupCode);

    /** 成员 ID → 显示名；查不到或未设显示名的不在结果里。 */
    Map<String, String> displayNames(TenantContext t, Collection<String> memberIds);

}
