package ai.neargo.opskit.platform.store.dao;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.platform.store.JsonColumns;
import ai.neargo.opskit.platform.store.row.MemberRow;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class MemberDao {

    private static final RowMapper<MemberRow> MAPPER = (rs, i) -> new MemberRow(
            rs.getString("account_id"), rs.getString("workspace_id"), rs.getString("member_id"), rs.getString("external_ref"),
            rs.getString("display_name"), JsonColumns.readMap(rs.getString("attrs")), JsonColumns.readSet(rs.getString("scopes")),
            rs.getString("source"), rs.getString("status"), (Long) rs.getObject("last_seen_at_ms", Long.class),
            rs.getLong("created_at_ms"), rs.getLong("updated_at_ms"));

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public MemberDao(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    /**
     * 不存在才插；已存在（含并发的另一方刚插）什么也不做、**不报错**。
     *
     * <p>不能用「插入 → 捕获唯一键冲突 → 再查」：PostgreSQL 里一条语句失败会让整个事务作废，
     * 之后的任何语句都报 25P02（DirectoryAppServiceTest 实测）。之后请用 {@link #findByExternalRefForUpdate} 取行。
     */
    public void insertIfAbsent(WorkspaceId ws, MemberRow r) {
        String sql = dialect.upsert("plat_member", List.of("workspace_id", "external_ref"),
                List.of("account_id", "workspace_id", "member_id", "external_ref", "display_name", "attrs", "scopes", "source", "status",
                        "last_seen_at_ms", "created_at_ms", "updated_at_ms"),
                List.of(), Set.of("attrs", "scopes"));
        jdbc.sql(sql).params(r.accountId(), ws.value(), r.memberId(), r.externalRef(), r.displayName(), JsonColumns.write(r.attrs()),
                JsonColumns.write(r.scopes()), r.source(), r.status(), r.lastSeenAtMs(), r.createdAtMs(), r.updatedAtMs()).update();
    }

    /**
     * 加锁读：读最新已提交的版本。MySQL / MariaDB 默认可重复读，普通 SELECT 读的是本事务开头的快照，
     * 看不到并发一方刚提交的行（DirectoryAppServiceTest 实测为 NoSuchElement）。
     */
    public Optional<MemberRow> findByExternalRefForUpdate(WorkspaceId ws, String externalRef) {
        return jdbc.sql("SELECT * FROM plat_member WHERE workspace_id = ? AND external_ref = ? FOR UPDATE")
                .params(ws.value(), externalRef).query(MAPPER).optional();
    }

    public Optional<MemberRow> findByExternalRef(WorkspaceId ws, String externalRef) {
        return jdbc.sql("SELECT * FROM plat_member WHERE workspace_id = ? AND external_ref = ?").params(ws.value(), externalRef).query(MAPPER).optional();
    }

    public Optional<MemberRow> findById(WorkspaceId ws, String memberId) {
        return jdbc.sql("SELECT * FROM plat_member WHERE workspace_id = ? AND member_id = ?").params(ws.value(), memberId).query(MAPPER).optional();
    }

    public List<MemberRow> findByExternalRefs(WorkspaceId ws, Collection<String> externalRefs) {
        if (externalRefs.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM plat_member WHERE workspace_id = :ws AND external_ref IN (:refs)")
                .param("ws", ws.value()).param("refs", externalRefs).query(MAPPER).list();
    }

    public void updateProfile(WorkspaceId ws, String memberId, String displayName, String attrsJson, String scopesJson, long now) {
        jdbc.sql("UPDATE plat_member SET display_name = ?, attrs = " + dialect.jsonParam() + ", scopes = " + dialect.jsonParam()
                        + ", updated_at_ms = ? WHERE workspace_id = ? AND member_id = ?")
                .params(displayName, attrsJson, scopesJson, now, ws.value(), memberId).update();
    }

    public void touch(WorkspaceId ws, String memberId, long now) {
        jdbc.sql("UPDATE plat_member SET last_seen_at_ms = ? WHERE workspace_id = ? AND member_id = ?").params(now, ws.value(), memberId).update();
    }
}
