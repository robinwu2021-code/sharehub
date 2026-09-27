package ai.neargo.opskit.platform.store.dao;

import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.platform.store.row.WorkspaceRow;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 空间的一切读写都带账户条件：凭证只属于一个账户，查别的账户的空间得到的是「不存在」，而不是「无权限」。
 */
public final class WorkspaceDao {

    private static final RowMapper<WorkspaceRow> MAPPER = (rs, i) -> new WorkspaceRow(
            rs.getString("account_id"), rs.getString("workspace_id"), rs.getString("name"), rs.getString("external_ref"),
            rs.getString("default_zone"), rs.getString("default_locale"), rs.getString("status"),
            rs.getLong("created_at_ms"), rs.getLong("updated_at_ms"));

    private final JdbcClient jdbc;

    public WorkspaceDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return false = 同账户下外部租户号已被占用（唯一键 uk_ext）
     *
     * <p>⚠️ 返回 false 后调用方必须立即结束事务（抛错回滚）：PostgreSQL 里撞唯一键会让整个事务作废，
     * 之后任何语句都会失败。需要「冲突了接着干」的场景用 upsert DO NOTHING + 加锁读（见 MemberDao）。
     */
    public boolean insert(AccountId accountId, WorkspaceRow row) {
        try {
            jdbc.sql("INSERT INTO plat_workspace (account_id, workspace_id, name, external_ref, default_zone, default_locale, status, created_at_ms, updated_at_ms) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(accountId.value(), row.workspaceId(), row.name(), row.externalRef(), row.defaultZone(),
                            row.defaultLocale(), row.status(), row.createdAtMs(), row.updatedAtMs())
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<WorkspaceRow> findById(AccountId accountId, String workspaceId) {
        return jdbc.sql("SELECT * FROM plat_workspace WHERE account_id = ? AND workspace_id = ?")
                .params(accountId.value(), workspaceId).query(MAPPER).optional();
    }

    public Optional<WorkspaceRow> findByExternalRef(AccountId accountId, String externalRef) {
        return jdbc.sql("SELECT * FROM plat_workspace WHERE account_id = ? AND external_ref = ?")
                .params(accountId.value(), externalRef).query(MAPPER).optional();
    }

    public List<WorkspaceRow> list(AccountId accountId) {
        return jdbc.sql("SELECT * FROM plat_workspace WHERE account_id = ? ORDER BY created_at_ms, workspace_id")
                .param(accountId.value()).query(MAPPER).list();
    }
}
