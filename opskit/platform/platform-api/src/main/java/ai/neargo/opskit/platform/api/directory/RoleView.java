package ai.neargo.opskit.platform.api.directory;

import java.util.Map;
import java.util.Set;

/** @param template true = 账户级模板角色（空间里没有同代号的覆盖时生效） */
public record RoleView(String code, Map<String, String> name, Set<String> permissions, boolean template) {
}
