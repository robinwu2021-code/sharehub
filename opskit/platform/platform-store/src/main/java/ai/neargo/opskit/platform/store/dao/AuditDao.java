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
}
