package ai.neargo.opskit.ticket.store.dao;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.ticket.store.TicketJson;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 工单（tkt_ticket）。状态与 SLA 列是只读快照：只经 {@link #updateSnapshot} 由流程变化同步写入，工单写路径不碰它们。
 */
public final class TicketDao {

    public record Row(String accountId, String workspaceId, String ticketNo, long typeDefinitionId, String typeCode, String typeVersion, String instanceNo,
            String title, String description, String priority, Integer priorityOrd, String resolution, Map<String, Object> fields, String requester,
            String owner, String assignee, String status, String statusCategory, boolean open, String slaState, Long slaNextDueAtMs, Long slaStartedAtMs,
            long slaPausedMs, boolean escalated, String mergedInto, String dedupeKey, String dedupeOpenKey, String createdBy, long createdAtMs,
            long updatedAtMs, Long closedAtMs, int version, String subjectType, String subjectRef, Map<String, Object> subjectDisplay) {
    }

    /** 快照：流程实例的状态 + SLA 摘要。 */
    public record Snapshot(String status, String statusCategory, boolean open, String slaState, Long slaNextDueAtMs, Long slaStartedAtMs, long slaPausedMs,
            String assignee) {
    }

    private static final RowMapper<Row> MAPPER = (rs, i) -> new Row(rs.getString("account_id"), rs.getString("workspace_id"), rs.getString("ticket_no"),
            rs.getLong("type_definition_id"), rs.getString("type_code"), rs.getString("type_version"), rs.getString("instance_no"), rs.getString("title"),
            rs.getString("description"), rs.getString("priority"), rs.getObject("priority_ord", Integer.class), rs.getString("resolution"),
            TicketJson.readMap(rs.getString("fields")), rs.getString("requester"), rs.getString("owner"), rs.getString("assignee"), rs.getString("status"),
            rs.getString("status_category"), rs.getInt("is_open") == 1, rs.getString("sla_state"), rs.getObject("sla_next_due_at_ms", Long.class),
            rs.getObject("sla_started_at_ms", Long.class), rs.getLong("sla_paused_ms"), rs.getInt("escalated") == 1, rs.getString("merged_into"),
            rs.getString("dedupe_key"), rs.getString("dedupe_open_key"), rs.getString("created_by"), rs.getLong("created_at_ms"),
            rs.getLong("updated_at_ms"), rs.getObject("closed_at_ms", Long.class), rs.getInt("version"), rs.getString("subject_type"),
            rs.getString("subject_ref"), TicketJson.readMap(rs.getString("subject_display")));

    private static final List<String> COLUMNS = List.of("account_id", "workspace_id", "ticket_no", "type_definition_id", "type_code", "type_version",
            "instance_no", "title", "description", "priority", "priority_ord", "fields", "requester", "status", "status_category", "is_open",
            "dedupe_key", "dedupe_open_key", "created_by", "created_at_ms", "updated_at_ms", "version", "subject_type", "subject_ref", "subject_display");

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public TicketDao(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    /**
     * 插入工单。带去重键时是「不存在才插」：同键已有未结单则什么也不做、不报错（PG 撞键会让事务作废，不能靠捕获异常）。
     * 插没插上由调用方随后 {@link #lockByDedupeOpenKey} 读回判断 —— MySQL 驱动默认返回的是匹配行数，影响行数不可信。
     */
    public void insert(WorkspaceId ws, Row r) {
        String sql = r.dedupeOpenKey() == null
                ? "INSERT INTO tkt_ticket (" + String.join(", ", COLUMNS) + ") VALUES (" + dialect.valuePlaceholders(COLUMNS, Set.of("fields", "subject_display")) + ")"
                : dialect.upsert("tkt_ticket", List.of("workspace_id", "dedupe_open_key"), COLUMNS, List.of(), Set.of("fields", "subject_display"));
        jdbc.sql(sql).params(r.accountId(), ws.value(), r.ticketNo(), r.typeDefinitionId(), r.typeCode(), r.typeVersion(), r.instanceNo(), r.title(),
                r.description(), r.priority(), r.priorityOrd(), TicketJson.write(r.fields()), r.requester(), r.status(), r.statusCategory(),
                r.open() ? 1 : 0, r.dedupeKey(), r.dedupeOpenKey(), r.createdBy(), r.createdAtMs(), r.updatedAtMs(), 0, r.subjectType(), r.subjectRef(),
                r.subjectDisplay() == null || r.subjectDisplay().isEmpty() ? null : TicketJson.write(r.subjectDisplay())).update();
    }

    /** 加锁读（读最新已提交版本，MySQL 可重复读下普通读看不到并发一方刚插的行）。 */
    public Optional<Row> lockByDedupeOpenKey(WorkspaceId ws, String dedupeOpenKey) {
        return jdbc.sql("SELECT * FROM tkt_ticket WHERE workspace_id = ? AND dedupe_open_key = ? FOR UPDATE")
                .params(ws.value(), dedupeOpenKey).query(MAPPER).optional();
    }

    public Optional<Row> find(WorkspaceId ws, String ticketNo) {
        return jdbc.sql("SELECT * FROM tkt_ticket WHERE workspace_id = ? AND ticket_no = ?").params(ws.value(), ticketNo).query(MAPPER).optional();
    }

    public Optional<Row> lock(WorkspaceId ws, String ticketNo) {
        return jdbc.sql("SELECT * FROM tkt_ticket WHERE workspace_id = ? AND ticket_no = ? FOR UPDATE").params(ws.value(), ticketNo).query(MAPPER).optional();
    }

    public Optional<Row> findByInstance(WorkspaceId ws, String instanceNo) {
        return jdbc.sql("SELECT * FROM tkt_ticket WHERE workspace_id = ? AND instance_no = ?").params(ws.value(), instanceNo).query(MAPPER).optional();
    }

    /** 建单时先用占位实例号插行，实例启动后回填。 */
    public int bindInstance(WorkspaceId ws, String ticketNo, String instanceNo) {
        return jdbc.sql("UPDATE tkt_ticket SET instance_no = ? WHERE workspace_id = ? AND ticket_no = ?").params(instanceNo, ws.value(), ticketNo).update();
    }

    /** 改内容（标题 / 描述 / 优先级 / 字段）；只有版本没变时成功。 */
    public int updateContent(WorkspaceId ws, String ticketNo, int expectedVersion, String title, String description, String priority, Integer priorityOrd,
            Map<String, Object> fields, long now) {
        return jdbc.sql("UPDATE tkt_ticket SET title = ?, description = ?, priority = ?, priority_ord = ?, fields = " + dialect.jsonParam()
                        + ", updated_at_ms = ?, version = version + 1 WHERE workspace_id = ? AND ticket_no = ? AND version = ?")
                .params(title, description, priority, priorityOrd, TicketJson.write(fields), now, ws.value(), ticketNo, expectedVersion).update();
    }

    public void setCurrentTask(WorkspaceId ws, String ticketNo, String taskNo) {
        jdbc.sql("UPDATE tkt_ticket SET current_task_no = ? WHERE workspace_id = ? AND ticket_no = ?").params(taskNo, ws.value(), ticketNo).update();
    }

    public void setEscalated(WorkspaceId ws, String ticketNo) {
        jdbc.sql("UPDATE tkt_ticket SET escalated = 1 WHERE workspace_id = ? AND ticket_no = ?").params(ws.value(), ticketNo).update();
    }

    public void setMergedInto(WorkspaceId ws, String ticketNo, String into) {
        jdbc.sql("UPDATE tkt_ticket SET merged_into = ? WHERE workspace_id = ? AND ticket_no = ?").params(into, ws.value(), ticketNo).update();
    }

    /** 某人名下的未结工单号（批量改派「按原受理人」）；取 limit + 1 条，调用方据此判断是否还有剩余。 */
    public List<String> openOfAssignee(WorkspaceId ws, String assignee, int limit) {
        return jdbc.sql("SELECT ticket_no FROM tkt_ticket WHERE workspace_id = ? AND assignee = ? AND is_open = 1 ORDER BY created_at_ms, ticket_no"
                        + dialect.limitOffset(limit + 1, 0))
                .params(ws.value(), assignee).query(String.class).list();
    }

    public void setResolution(WorkspaceId ws, String ticketNo, String resolution) {
        jdbc.sql("UPDATE tkt_ticket SET resolution = ? WHERE workspace_id = ? AND ticket_no = ?").params(resolution, ws.value(), ticketNo).update();
    }

    /**
     * 同步快照（由流程变化触发，同事务）。关单时清去重占位（同键可再建新单）并记关闭时刻；重开时清关闭时刻、恢复占位。
     */
    public void updateSnapshot(WorkspaceId ws, String ticketNo, Snapshot s, long now) {
        jdbc.sql("UPDATE tkt_ticket SET status = ?, status_category = ?, is_open = ?, sla_state = ?, sla_next_due_at_ms = ?, sla_started_at_ms = ?, "
                        + "sla_paused_ms = ?, assignee = ?, "
                        + "closed_at_ms = CASE WHEN ? = 1 THEN NULL WHEN closed_at_ms IS NULL THEN ? ELSE closed_at_ms END, "
                        + "dedupe_open_key = CASE WHEN ? = 1 THEN dedupe_key ELSE NULL END, updated_at_ms = ? "
                        + "WHERE workspace_id = ? AND ticket_no = ?")
                .params(s.status(), s.statusCategory(), s.open() ? 1 : 0, s.slaState(), s.slaNextDueAtMs(), s.slaStartedAtMs(), s.slaPausedMs(), s.assignee(),
                        s.open() ? 1 : 0, now, s.open() ? 1 : 0, now, ws.value(), ticketNo)
                .update();
    }
}
