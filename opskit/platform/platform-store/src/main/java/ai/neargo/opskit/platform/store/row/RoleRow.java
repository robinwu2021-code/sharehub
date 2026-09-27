package ai.neargo.opskit.platform.store.row;

import java.util.Map;
import java.util.Set;

/** workspaceId 为空串 = 账户级模板。 */
public record RoleRow(String accountId, String workspaceId, String roleCode, Map<String, String> name, Set<String> permissions, long updatedAtMs) {
}
