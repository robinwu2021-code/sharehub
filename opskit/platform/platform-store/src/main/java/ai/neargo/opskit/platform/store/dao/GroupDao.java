package ai.neargo.opskit.platform.store.dao;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.platform.store.JsonColumns;
import ai.neargo.opskit.platform.store.row.GroupRow;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class GroupDao {

    private static final RowMapper<GroupRow> MAPPER = (rs, i) -> new GroupRow(
            rs.getString("account_id"), rs.getString("workspace_id"), rs.getString("group_code"), JsonColumns.readText(rs.getString("name")),
            rs.getString("kind"), (Integer) rs.getObject("claim_timeout_sec", Integer.class), rs.getString("order_by"),
            rs.getString("external_ref"), rs.getString("source"), rs.getString("status"), rs.getLong("updated_at_ms"));

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public GroupDao(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    /** 有则改名称 / 类型 / 队列参数，无则建。 */
    public void upsert(WorkspaceId ws, GroupRow r) {
        String sql = dialect.upsert("plat_group",
                List.of("workspace_id", "group_code"),
                List.of("account_id", "workspace_id", "group_code", "name", "kind", "claim_timeout_sec", "order_by", "external_ref", "source", "status", "updated_at_ms"),
                List.of("name", "kind", "claim_timeout_sec", "order_by", "updated_at_ms"),
                Set.of("name"));
        jdbc.sql(sql)
                .params(r.accountId(), ws.value(), r.groupCode(), JsonColumns.write(r.name()), r.kind(), r.claimTimeoutSec(), r.orderBy(),
                        r.externalRef(), r.source(), r.status(), r.updatedAtMs())
                .update();
    }

    /** 令牌里出现、目录里还没有的组：自动建成 TEAM（来源 CLAIM）。已存在的一概不动。 */
    public void insertMissing(WorkspaceId ws, String accountId, Collection<String> codes, String source, long now) {
        for (String code : codes) {
            String sql = dialect.upsert("plat_group", List.of("workspace_id", "group_code"),
                    List.of("account_id", "workspace_id", "group_code", "name", "kind", "source", "status", "updated_at_ms"), List.of(), Set.of("name"));
            jdbc.sql(sql)
                    .params(accountId, ws.value(), code, JsonColumns.write(java.util.Map.of("en", code)), "TEAM", source, "ACTIVE", now)
                    .update();
        }
    }

    public Optional<GroupRow> find(WorkspaceId ws, String code) {
        return jdbc.sql("SELECT * FROM plat_group WHERE workspace_id = ? AND group_code = ?").params(ws.value(), code).query(MAPPER).optional();
    }
}
