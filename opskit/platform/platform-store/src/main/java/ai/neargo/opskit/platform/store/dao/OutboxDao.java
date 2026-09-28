package ai.neargo.opskit.platform.store.dao;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.platform.store.row.OutboxRow;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 发件箱。投递方是 db-scheduler 的周期任务（同一时刻只有一个实例在跑），所以取行不加锁；
 * 手工重投只改 next_at / failed，不与投递争行。
 */
public final class OutboxDao {

    private static final RowMapper<OutboxRow> MAPPER = (rs, i) -> new OutboxRow(
            rs.getLong("id"), rs.getString("account_id"), rs.getString("workspace_id"), rs.getString("event_type"), rs.getString("idem_key"),
            rs.getString("payload"), rs.getInt("attempts"), rs.getLong("next_at_ms"), (Long) rs.getObject("done_at_ms", Long.class),
            rs.getInt("failed") == 1, rs.getString("last_error"), rs.getLong("created_at_ms"));

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public OutboxDao(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    /** 同空间同类型同幂等键只入箱一次（写请求重试会再次触发同一事件）；重复不报错。 */
    public void enqueue(WorkspaceId ws, String accountId, String eventType, String idemKey, String payload, long now) {
        String sql = dialect.upsert("plat_outbox", List.of("workspace_id", "event_type", "idem_key"),
                List.of("account_id", "workspace_id", "event_type", "idem_key", "payload", "attempts", "next_at_ms", "failed", "created_at_ms"),
                List.of());
        jdbc.sql(sql).params(accountId, ws.value(), eventType, idemKey, payload, 0, now, 0, now).update();
    }

    /** 到期待投递的行（跨空间：投递是全局任务）。第一个参数显式写 UNKNOWN，让「没有租户条件」在调用处显形。 */
    public List<OutboxRow> due(AccountId unknown, long now, int limit) {
        if (!AccountId.UNKNOWN.equals(unknown)) {
            throw new IllegalArgumentException("outbox relay is cross-tenant by design");
        }
        return jdbc.sql("SELECT * FROM plat_outbox WHERE done_at_ms IS NULL AND failed = 0 AND next_at_ms <= ? ORDER BY next_at_ms, id"
                        + dialect.limitOffset(limit, 0))
                .param(now).query(MAPPER).list();
    }

    /** 积压（跨空间，指标用）：未投完且未判失败的行数，以及其中已到期仍未投出的行数。 */
    public long[] backlog(AccountId unknown, long now) {
        if (!AccountId.UNKNOWN.equals(unknown)) {
            throw new IllegalArgumentException("outbox backlog is cross-tenant by design");
        }
        return jdbc.sql("SELECT COUNT(*), COALESCE(SUM(CASE WHEN next_at_ms <= ? THEN 1 ELSE 0 END), 0), "
                        + "COALESCE(SUM(CASE WHEN failed = 1 THEN 1 ELSE 0 END), 0) FROM plat_outbox WHERE done_at_ms IS NULL")
                .param(now).query((rs, i) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)}).single();
    }

    public Optional<OutboxRow> find(AccountId account, long id) {
        return jdbc.sql("SELECT * FROM plat_outbox WHERE account_id = ? AND id = ?").params(account.value(), id).query(MAPPER).optional();
    }

    public void markDone(AccountId account, long id, long now) {
        jdbc.sql("UPDATE plat_outbox SET done_at_ms = ?, last_error = NULL WHERE account_id = ? AND id = ?").params(now, account.value(), id).update();
    }

    public void markRetry(AccountId account, long id, int attempts, long nextAt, boolean failed, String error) {
        jdbc.sql("UPDATE plat_outbox SET attempts = ?, next_at_ms = ?, failed = ?, last_error = ? WHERE account_id = ? AND id = ?")
                .params(attempts, nextAt, failed ? 1 : 0, error, account.value(), id).update();
    }

    /** 手工重投：清失败标记、立即到期；已完成的不动。 */
    public int redeliver(AccountId account, long id, long now) {
        return jdbc.sql("UPDATE plat_outbox SET failed = 0, next_at_ms = ? WHERE account_id = ? AND id = ? AND done_at_ms IS NULL")
                .params(now, account.value(), id).update();
    }

    /**
     * 某空间（ws 为空 = 账户下全部）的事件（接入管理页：投递状态）。state：FAILED = 重试耗尽待人工；PENDING = 未完成（含重试中）；DONE；空 = 全部。
     * 新的在前。
     */
    public List<OutboxRow> query(AccountId account, WorkspaceId ws, String state, String eventType, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM plat_outbox WHERE account_id = ?");
        List<Object> ps = new java.util.ArrayList<>(List.of(account.value()));
        appendWorkspace(sql, ps, ws);
        appendState(sql, state);
        if (eventType != null) {
            sql.append(" AND event_type = ?");
            ps.add(eventType);
        }
        sql.append(" ORDER BY id DESC").append(dialect.limitOffset(limit, offset));
        return jdbc.sql(sql.toString()).params(ps).query(MAPPER).list();
    }

    public long count(AccountId account, WorkspaceId ws, String state, String eventType) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM plat_outbox WHERE account_id = ?");
        List<Object> ps = new java.util.ArrayList<>(List.of(account.value()));
        appendWorkspace(sql, ps, ws);
        appendState(sql, state);
        if (eventType != null) {
            sql.append(" AND event_type = ?");
            ps.add(eventType);
        }
        return jdbc.sql(sql.toString()).params(ps).query(Long.class).single();
    }

    /** ws 为空 = 账户下全部空间（运维面）。 */
    private static void appendWorkspace(StringBuilder sql, List<Object> ps, WorkspaceId ws) {
        if (ws != null) {
            sql.append(" AND workspace_id = ?");
            ps.add(ws.value());
        }
    }

    private static void appendState(StringBuilder sql, String state) {
        if ("FAILED".equals(state)) {
            sql.append(" AND done_at_ms IS NULL AND failed = 1");
        } else if ("PENDING".equals(state)) {
            sql.append(" AND done_at_ms IS NULL AND failed = 0");
        } else if ("DONE".equals(state)) {
            sql.append(" AND done_at_ms IS NOT NULL");
        }
    }

    /**
     * 清掉早于 before 送达的事件及其投递记录，一批最多 limit 条；返回本批删除的事件数（0 = 清完）。跨空间是有意为之（全局维护任务）。
     * 先查主键再按主键删：按范围 DELETE 在 MySQL 可重复读下会加间隙锁，与投递中的写入互相阻塞。
     */
    public int purgeDeliveredBefore(AccountId unknown, long before, int limit) {
        if (!AccountId.UNKNOWN.equals(unknown)) {
            throw new IllegalArgumentException("outbox purge is cross-tenant by design");
        }
        List<Long> ids = jdbc.sql("SELECT id FROM plat_outbox WHERE done_at_ms IS NOT NULL AND done_at_ms < ? ORDER BY id" + dialect.limitOffset(limit, 0))
                .param(before).query(Long.class).list();
        if (ids.isEmpty()) {
            return 0;
        }
        jdbc.sql("DELETE FROM plat_webhook_delivery WHERE outbox_id IN (:ids)").param("ids", ids).update();
        return jdbc.sql("DELETE FROM plat_outbox WHERE id IN (:ids)").param("ids", ids).update();
    }
}
