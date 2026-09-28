package ai.neargo.opskit.platform.core.embedded;

import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.platform.api.account.WorkspaceRef;
import ai.neargo.opskit.platform.client.WorkspaceClient;
import ai.neargo.opskit.platform.core.app.TenancyAppService;
import java.time.ZoneId;

/** {@link WorkspaceClient} 的进程内实现。 */
public class EmbeddedWorkspaceClient implements WorkspaceClient {

    private final TenancyAppService tenancy;

    public EmbeddedWorkspaceClient(TenancyAppService tenancy) {
        this.tenancy = tenancy;
    }

    @Override
    public ZoneId zone(TenantContext t) {
        String z = tenancy.resolve(t.accountId(), new WorkspaceRef.ById(t.workspaceId().value())).defaultZone();
        return ZoneId.of(z == null || z.isBlank() ? "UTC" : z);
    }
}
