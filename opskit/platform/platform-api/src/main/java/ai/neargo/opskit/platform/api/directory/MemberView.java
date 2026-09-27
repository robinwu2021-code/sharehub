package ai.neargo.opskit.platform.api.directory;

import java.util.Map;
import java.util.Set;

public record MemberView(String workspaceId, String memberId, String externalRef, String displayName,
        Map<String, Object> attrs, Set<String> scopes, DirectorySource source, String status) {
}
