package ai.neargo.opskit.platform.store.dao;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.tenancy.AccountId;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class AuditDao {

    public record Entry(String accountId, String workspaceId, String actor, String action, String target, String detail, long atMs) {
    }

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public AuditDao(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    /** workspaceId 空串 = 账户级操作（模板、应用、凭证）。 */
    public void insert(AccountId account, String workspaceId, String actor, String action, String target, String detailJson, long now) {
        jdbc.sql("INSERT INTO plat_audit_log (account_id, workspace_id, actor, action, target, detail, at_ms) VALUES (?, ?, ?, ?, ?, "
                        + dialect.jsonParam() + ", ?)")
                .params(account.value(), workspaceId, actor, action, target, detailJson, now).update();
    }

    public List<Entry> query(AccountId account, String workspaceId, long fromMs, long toMs, int limit) {
        return jdbc.sql("SELECT * FROM plat_audit_log WHERE account_id = ? AND workspace_id = ? AND at_ms >= ? AND at_ms < ? ORDER BY at_ms DESC, id DESC"
                        + dialect.limitOffset(limit, 0))
                .params(account.value(), workspaceId, fromMs, toMs)
                .query((rs, i) -> new Entry(rs.getString("account_id"), rs.getString("workspace_id"), rs.getString("actor"),
                        rs.getString("action"), rs.getString("target"), rs.getString("detail"), rs.getLong("at_ms")))
                .list();
    }

    /**
     * 账户级审计查询（运维面）。workspaceId：空 = 全部（含账户级操作）；{@code ""} = 只看账户级操作。新的在前。
     */
    public List<Entry> queryAccount(AccountId account, String workspaceId, String action, long fromMs, long toMs, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM plat_audit_log WHERE account_id = ? AND at_ms >= ? AND at_ms < ?");
        List<Object> ps = new java.util.ArrayList<>(List.of(account.value(), fromMs, toMs));
        filter(sql, ps, workspaceId, action);
        sql.append(" ORDER BY at_ms DESC, id DESC").append(dialect.limitOffset(limit, offset));
        return jdbc.sql(sql.toString()).params(ps)
                .query((rs, i) -> new Entry(rs.getString("account_id"), rs.getString("workspace_id"), rs.getString("actor"),
                        rs.getString("action"), rs.getString("target"), rs.getString("detail"), rs.getLong("at_ms")))
                .list();
    }

    public long countAccount(AccountId account, String workspaceId, String action, long fromMs, long toMs) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM plat_audit_log WHERE account_id = ? AND at_ms >= ? AND at_ms < ?");
        List<Object> ps = new java.util.ArrayList<>(List.of(account.value(), fromMs, toMs));
        filter(sql, ps, workspaceId, action);
        return jdbc.sql(sql.toString()).params(ps).query(Long.class).single();
    }

    private static void filter(StringBuilder sql, List<Object> ps, String workspaceId, String action) {
        if (workspaceId != null) {
            sql.append(" AND workspace_id = ?");
            ps.add(workspaceId);
        }
        if (action != null) {
            sql.append(" AND action = ?");
            ps.add(action);
        }
    }
}
