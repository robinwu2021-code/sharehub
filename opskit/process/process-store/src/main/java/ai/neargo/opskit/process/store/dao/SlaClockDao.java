package ai.neargo.opskit.process.store.dao;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * SLA 计时器（proc_sla_clock）。一行 = （实例, 策略, 指标）。所有变化都是带版本的条件更新，0 行即并发冲突；
 * {@code epoch} 每次重排定时都 +1，旧的到期 / 预警定时比对不上自然作废。
 */
public final class SlaClockDao {

    /**
     * @param state PENDING（未起算）/ RUNNING / WARNING / PAUSED / BREACHED / MET / CANCELLED
     */
    public record Row(String accountId, String workspaceId, String clockId, String instanceNo, String policyCode, String policyVersion,
            String metricCode, Long targetMs, String calendarCode, String state, Long startedAtMs, Long dueAtMs, Long warnAtMs, long pausedMs,
            Long pausedSinceMs, Long breachedAtMs, Long stoppedAtMs, int epoch, int version) {

        public boolean active() {
            return "RUNNING".equals(state) || "WARNING".equals(state) || "PAUSED".equals(state) || "BREACHED".equals(state) && stoppedAtMs == null;
        }
    }

    private static final RowMapper<Row> MAPPER = (rs, i) -> new Row(rs.getString("account_id"), rs.getString("workspace_id"), rs.getString("clock_id"),
            rs.getString("instance_no"), rs.getString("policy_code"), rs.getString("policy_version"), rs.getString("metric_code"),
            rs.getObject("target_ms", Long.class), rs.getString("calendar_code"), rs.getString("state"), rs.getObject("started_at_ms", Long.class),
            rs.getObject("due_at_ms", Long.class), rs.getObject("warn_at_ms", Long.class), rs.getLong("paused_ms"),
            rs.getObject("paused_since_ms", Long.class), rs.getObject("breached_at_ms", Long.class), rs.getObject("stopped_at_ms", Long.class),
            rs.getInt("epoch"), rs.getInt("version"));

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public SlaClockDao(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    public void insert(WorkspaceId ws, Row r) {
        jdbc.sql("INSERT INTO proc_sla_clock (account_id, workspace_id, clock_id, instance_no, policy_code, policy_version, metric_code, target_ms, "
                        + "calendar_code, state, started_at_ms, due_at_ms, warn_at_ms, paused_ms, paused_since_ms, breached_at_ms, stopped_at_ms, epoch, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)")
                .params(r.accountId(), ws.value(), r.clockId(), r.instanceNo(), r.policyCode(), r.policyVersion(), r.metricCode(), r.targetMs(),
                        r.calendarCode(), r.state(), r.startedAtMs(), r.dueAtMs(), r.warnAtMs(), r.pausedMs(), r.pausedSinceMs(), r.breachedAtMs(),
                        r.stoppedAtMs(), r.epoch())
                .update();
    }

    public Optional<Row> find(WorkspaceId ws, String clockId) {
        return jdbc.sql("SELECT * FROM proc_sla_clock WHERE workspace_id = ? AND clock_id = ?").params(ws.value(), clockId).query(MAPPER).optional();
    }

    public List<Row> ofInstance(WorkspaceId ws, String instanceNo) {
        return jdbc.sql("SELECT * FROM proc_sla_clock WHERE workspace_id = ? AND instance_no = ? ORDER BY policy_code, metric_code")
                .params(ws.value(), instanceNo).query(MAPPER).list();
    }

    public List<Row> ofInstances(WorkspaceId ws, Collection<String> instanceNos) {
        if (instanceNos.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM proc_sla_clock WHERE workspace_id = :ws AND instance_no IN (:nos) ORDER BY instance_no, policy_code, metric_code")
                .param("ws", ws.value()).param("nos", instanceNos).query(MAPPER).list();
    }

    /** 写回可变字段；只有版本没变时成功（0 行 = 并发的另一方先改了）。 */
    public int update(WorkspaceId ws, Row r) {
        return jdbc.sql("UPDATE proc_sla_clock SET target_ms = ?, calendar_code = ?, state = ?, started_at_ms = ?, due_at_ms = ?, warn_at_ms = ?, "
                        + "paused_ms = ?, paused_since_ms = ?, breached_at_ms = ?, stopped_at_ms = ?, epoch = ?, version = version + 1 "
                        + "WHERE workspace_id = ? AND clock_id = ? AND version = ?")
                .params(r.targetMs(), r.calendarCode(), r.state(), r.startedAtMs(), r.dueAtMs(), r.warnAtMs(), r.pausedMs(), r.pausedSinceMs(),
                        r.breachedAtMs(), r.stoppedAtMs(), r.epoch(), ws.value(), r.clockId(), r.version())
                .update();
    }

    /** 兜底扫描（跨空间）：未暂停、已过到期或预警时刻仍在计时的。 */
    public List<Row> overdue(AccountId unknown, long now, int limit) {
        if (!AccountId.UNKNOWN.equals(unknown)) {
            throw new IllegalArgumentException("sla reconcile scan is cross-tenant by design");
        }
        return jdbc.sql("SELECT * FROM proc_sla_clock WHERE (state IN ('RUNNING','WARNING') AND due_at_ms <= ?) OR (state = 'RUNNING' AND warn_at_ms <= ?) "
                        + "ORDER BY due_at_ms" + dialect.limitOffset(limit, 0))
                .params(now, now).query(MAPPER).list();
    }
}
