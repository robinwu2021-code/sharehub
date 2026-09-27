package ai.neargo.opskit.platform.api.app;

import java.util.Set;

/** 凭证校验通过后的调用方。空间访问判定只看这里，不看请求里自称的任何值。 */
public record AuthenticatedApp(String accountId, String appId, boolean allWorkspaces, Set<String> workspaceIds) {
    public AuthenticatedApp {
        workspaceIds = Set.copyOf(workspaceIds);
    }

    public boolean canAccess(String workspaceId) {
        return allWorkspaces || workspaceIds.contains(workspaceId);
    }
}
