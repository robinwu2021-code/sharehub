package ai.neargo.opskit.platform.store.row;

import java.util.Map;
import java.util.Set;

public record MemberRow(String accountId, String workspaceId, String memberId, String externalRef, String displayName,
        Map<String, Object> attrs, Set<String> scopes, String source, String status, Long lastSeenAtMs, long createdAtMs, long updatedAtMs) {
}
