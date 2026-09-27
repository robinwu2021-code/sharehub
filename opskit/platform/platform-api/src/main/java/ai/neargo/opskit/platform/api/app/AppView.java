package ai.neargo.opskit.platform.api.app;

import java.util.List;

/** 对外视图只带凭证末 4 位提示，永远不带凭证与签名密钥。 */
public record AppView(String accountId, String appId, String name, boolean allWorkspaces, List<String> workspaceIds,
        String callbackBaseUrl, String credentialHint, String status, long createdAtMs) {
}
