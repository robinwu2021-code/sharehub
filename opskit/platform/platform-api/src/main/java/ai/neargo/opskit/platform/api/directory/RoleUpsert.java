package ai.neargo.opskit.platform.api.directory;

import java.util.Map;
import java.util.Set;

/** @param permissions 产品权限目录中的码（{@link ai.neargo.opskit.platform.api.Permission}），目录外的码拒绝 */
public record RoleUpsert(String code, Map<String, String> name, Set<String> permissions) {
    public RoleUpsert {
        name = name == null ? Map.of() : Map.copyOf(name);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }
}
