package ai.neargo.opskit.platform.store.dao;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.platform.store.JsonColumns;
import ai.neargo.opskit.platform.store.row.RoleRow;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class RoleDao {

    private static final RowMapper<RoleRow> MAPPER = (rs, i) -> new RoleRow(
            rs.getString("account_id"), rs.getString("workspace_id"), rs.getString("role_code"),
            JsonColumns.readText(rs.getString("name")), JsonColumns.readSet(rs.getString("permissions")), rs.getLong("updated_at_ms"));

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public RoleDao(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    /** workspace 为 {@link WorkspaceId#TEMPLATE} 时写账户级模板。 */
    public void upsert(AccountId account, WorkspaceId ws, RoleRow r) {
        String sql = dialect.upsert("plat_role", List.of("account_id", "workspace_id", "role_code"),
                List.of("account_id", "workspace_id", "role_code", "name", "permissions", "updated_at_ms"),
                List.of("name", "permissions", "updated_at_ms"), Set.of("name", "permissions"));
        jdbc.sql(sql)
                .params(account.value(), ws.value(), r.roleCode(), JsonColumns.write(r.name()), JsonColumns.write(r.permissions()), r.updatedAtMs())
                .update();
    }

    /**
     * 某空间可见的角色：该空间自己的 + 账户模板。同代号时空间覆盖模板（在调用方按代号合并）。
     */
    public List<RoleRow> visibleIn(AccountId account, WorkspaceId ws, Collection<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM plat_role WHERE account_id = :a AND workspace_id IN ('', :ws) AND role_code IN (:codes)")
                .param("a", account.value()).param("ws", ws.value()).param("codes", codes).query(MAPPER).list();
    }
}
