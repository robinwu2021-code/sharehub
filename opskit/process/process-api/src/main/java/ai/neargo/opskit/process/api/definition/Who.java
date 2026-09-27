package ai.neargo.opskit.process.api.definition;

import java.util.Set;

/**
 * 谁能走这条迁移：任一集合匹配即可。四个集合都为空 = 缺省「当前办理人」。
 *
 * @param participantRoles 参与者角色（REQUESTER / OWNER / ASSIGNEE / WATCHER / 自定义）
 */
public record Who(Set<String> participantRoles, Set<String> roles, Set<String> groups, Set<String> perms) {
    public static final Who ASSIGNEE = new Who(Set.of(), Set.of(), Set.of(), Set.of());

    public Who {
        participantRoles = participantRoles == null ? Set.of() : Set.copyOf(participantRoles);
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        groups = groups == null ? Set.of() : Set.copyOf(groups);
        perms = perms == null ? Set.of() : Set.copyOf(perms);
    }

    public boolean isDefault() {
        return participantRoles.isEmpty() && roles.isEmpty() && groups.isEmpty() && perms.isEmpty();
    }
}
