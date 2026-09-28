package ai.neargo.opskit.platform.core.embedded;

import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.platform.api.directory.GroupView;
import ai.neargo.opskit.platform.client.DirectoryClient;
import ai.neargo.opskit.platform.core.app.DirectoryAppService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@link DirectoryClient} 的进程内实现：直接调平台目录服务（同进程同事务）。 */
public class EmbeddedDirectoryClient implements DirectoryClient {

    private final DirectoryAppService directory;

    public EmbeddedDirectoryClient(DirectoryAppService directory) {
        this.directory = directory;
    }

    @Override
    public Optional<GroupView> findGroup(TenantContext t, String groupCode) {
        return directory.findGroup(t, groupCode);
    }

    @Override
    public List<String> membersOf(TenantContext t, String groupCode) {
        return directory.membersOf(t, groupCode);
    }

    @Override
    public List<String> membersWithAttr(TenantContext t, String key, Collection<String> values, int limit) {
        return directory.membersWithAttr(t, key, values, limit);
    }

    @Override
    public Map<String, String> displayNames(TenantContext t, Collection<String> memberIds) {
        return directory.displayNames(t, memberIds);
    }
}
