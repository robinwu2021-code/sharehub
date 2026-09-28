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

    /** 空间内 ACTIVE 成员的 ID 与属性（按属性筛成员用；只取两列）。 */
    public List<java.util.Map.Entry<String, java.util.Map<String, Object>>> activeAttrs(WorkspaceId ws) {
        return jdbc.sql("SELECT member_id, attrs FROM plat_member WHERE workspace_id = ? AND status = 'ACTIVE' ORDER BY member_id").param(ws.value())
                .query((rs, i) -> java.util.Map.entry(rs.getString(1), JsonColumns.readMap(rs.getString(2)))).list();
    }

    public List<MemberRow> findByIds(WorkspaceId ws, Collection<String> memberIds) {
        if (memberIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM plat_member WHERE workspace_id = :ws AND member_id IN (:ids)")
                .param("ws", ws.value()).param("ids", memberIds).query(MAPPER).list();
    }

    /** 成员选项查询：关键词匹配显示名 / 宿主编号，可按组筛；只列 ACTIVE。列表与 {@link #count} 共用同一段条件。 */
    public List<MemberRow> query(WorkspaceId ws, String keyword, String group, int offset, int limit) {
        return jdbc.sql("SELECT m.* " + queryWhere(keyword, group) + " ORDER BY m.display_name, m.member_id LIMIT :limit OFFSET :offset")
                .params(queryParams(ws, keyword, group)).param("limit", limit).param("offset", offset).query(MAPPER).list();
    }

    public long count(WorkspaceId ws, String keyword, String group) {
        return jdbc.sql("SELECT COUNT(*) " + queryWhere(keyword, group)).params(queryParams(ws, keyword, group)).query(Long.class).single();
    }

    private static String queryWhere(String keyword, String group) {
        StringBuilder sql = new StringBuilder("FROM plat_member m WHERE m.workspace_id = :ws AND m.status = 'ACTIVE'");
        if (keyword != null) {
            sql.append(" AND (LOWER(m.display_name) LIKE :kw OR LOWER(m.external_ref) LIKE :kw)");
        }
        if (group != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM plat_group_member g WHERE g.workspace_id = m.workspace_id AND g.member_id = m.member_id AND g.group_code = :grp)");
        }
        return sql.toString();
    }

    private static java.util.Map<String, Object> queryParams(WorkspaceId ws, String keyword, String group) {
        java.util.Map<String, Object> ps = new java.util.HashMap<>();
        ps.put("ws", ws.value());
        if (keyword != null) {
            ps.put("kw", "%" + keyword.toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (group != null) {
            ps.put("grp", group);
        }
        return ps;
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
