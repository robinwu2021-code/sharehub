package ai.neargo.opskit.platform.api.directory;

import java.util.Map;

/** @param claimTimeoutSec 仅 QUEUE：领取后多久未办完自动放回；空则用系统缺省 */
public record GroupUpsert(String code, Map<String, String> name, GroupKind kind, Integer claimTimeoutSec, String orderBy) {
    public GroupUpsert {
        name = name == null ? Map.of() : Map.copyOf(name);
        kind = kind == null ? GroupKind.TEAM : kind;
    }
}
