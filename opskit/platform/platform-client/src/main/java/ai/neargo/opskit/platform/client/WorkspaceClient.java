package ai.neargo.opskit.platform.client;

import ai.neargo.opskit.commons.tenancy.TenantContext;
import java.time.ZoneId;

/** 空间信息的客户端（上层按空间的业务时区算「今天」，21/02 统计口径）。 */
public interface WorkspaceClient {

    /** 空间的业务时区（开户时设定）。 */
    ZoneId zone(TenantContext t);
}
