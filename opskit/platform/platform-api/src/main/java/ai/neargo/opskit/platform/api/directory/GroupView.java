package ai.neargo.opskit.platform.api.directory;

import java.util.Map;

public record GroupView(String workspaceId, String code, Map<String, String> name, GroupKind kind,
        Integer claimTimeoutSec, String orderBy, DirectorySource source) {
}
