package ai.neargo.opskit.platform.store.row;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** weekly 每项为 {@code {"day":"MONDAY","from":540,"to":1080}}（分钟）；workspaceId 空串 = 账户级模板。 */
public record CalendarRow(String accountId, String workspaceId, String calendarCode, String zone,
        List<Map<String, Object>> weekly, Set<String> holidays, String updatedBy, long updatedAtMs) {
}
