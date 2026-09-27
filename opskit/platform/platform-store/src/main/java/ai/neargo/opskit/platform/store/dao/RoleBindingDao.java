package ai.neargo.opskit.platform.store.dao;

import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class RoleBindingDao {

    private final JdbcClient jdbc;
    private final ai.neargo.opskit.commons.jdbc.Dialect dialect;

    public RoleBindingDao(JdbcClient jdbc, ai.neargo.opskit.commons.jdbc.Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    /** 这些主体（成员本人 + 其所在组）绑定的角色代号。 */
    public Set<String> rolesOf(WorkspaceId ws, Collection<String> principals) {
        if (principals.isEmpty()) {
            return Set.of();
        }
        return new TreeSet<>(jdbc.sql("SELECT DISTINCT role_code FROM plat_role_binding WHERE workspace_id = :ws AND principal IN (:p)")
                .param("ws", ws.value()).param("p", principals).query(String.class).list());
    }

    /** 已存在（任何来源）则不动；并发重复不报错。 */
    public void bind(WorkspaceId ws, String roleCode, String principal, String source, long now) {
        jdbc.sql(dialect.upsert("plat_role_binding", List.of("workspace_id", "role_code", "principal"),
                        List.of("workspace_id", "role_code", "principal", "source", "updated_at_ms"), List.of()))
                .params(ws.value(), roleCode, principal, source, now).update();
    }

    public List<String> rolesOfPrincipalBySource(WorkspaceId ws, String principal, String source) {
        return jdbc.sql("SELECT role_code FROM plat_role_binding WHERE workspace_id = ? AND principal = ? AND source = ?")
                .params(ws.value(), principal, source).query(String.class).list();
    }

    public void unbindBySource(WorkspaceId ws, String roleCode, String principal, String source) {
        jdbc.sql("DELETE FROM plat_role_binding WHERE workspace_id = ? AND role_code = ? AND principal = ? AND source = ?")
                .params(ws.value(), roleCode, principal, source).update();
    }
}
