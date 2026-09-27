package ai.neargo.opskit.platform.api.app;

import java.util.List;

/**
 * @param allWorkspaces   true = 可访问账户下全部空间（含以后新开的；伙伴应用通常如此）
 * @param workspaceIds    allWorkspaces 为 false 时生效的空间列表
 * @param callbackBaseUrl webhook 回调基址（可空，订阅时也可给完整 URL）
 */
public record CreateAppRequest(String name, boolean allWorkspaces, List<String> workspaceIds, String callbackBaseUrl) {
    public CreateAppRequest {
        workspaceIds = workspaceIds == null ? List.of() : List.copyOf(workspaceIds);
    }
}
