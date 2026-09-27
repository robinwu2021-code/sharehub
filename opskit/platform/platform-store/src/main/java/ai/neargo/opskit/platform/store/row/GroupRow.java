package ai.neargo.opskit.platform.store.row;

import java.util.Map;

public record GroupRow(String accountId, String workspaceId, String groupCode, Map<String, String> name, String kind,
        Integer claimTimeoutSec, String orderBy, String externalRef, String source, String status, long updatedAtMs) {
}
