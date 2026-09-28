package ai.neargo.opskit.svc.wiring;

import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.platform.api.directory.GroupKind;
import ai.neargo.opskit.platform.core.app.DirectoryAppService;
import ai.neargo.opskit.process.core.port.Directory;
import java.util.List;
import java.util.Optional;

/** 把平台目录接给流程层（流程层只认 {@link Directory} 端口，不依赖平台 core，21/02 §二）。 */
public class PlatformDirectory implements Directory {

    private final DirectoryAppService directory;

    public PlatformDirectory(DirectoryAppService directory) {
        this.directory = directory;
    }

    @Override
    public List<String> membersOf(TenantContext t, String groupCode) {
        return directory.membersOf(t, groupCode);
    }

    @Override
    public Optional<Integer> claimTimeoutSec(TenantContext t, String groupCode) {
        return directory.findGroup(t, groupCode).map(g -> g.claimTimeoutSec());
    }

    @Override
    public boolean isQueue(TenantContext t, String groupCode) {
        return directory.findGroup(t, groupCode).map(g -> g.kind() == GroupKind.QUEUE).orElse(false);
    }
}
