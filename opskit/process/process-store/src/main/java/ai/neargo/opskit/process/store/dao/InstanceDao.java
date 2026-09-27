package ai.neargo.opskit.process.store.dao;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.process.store.ProcessJson;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 流程实例（proc_instance）。一切变更为条件更新（原状态 + 版本号），0 行即并发冲突。 */
public final class InstanceDao {

    public record Row(String accountId, String workspaceId, String instanceNo, long definitionId, String processCode, String processVersion,
            String subjectType, String subjectRef, Map<String, Object> subjectDisplay, String status, String statusCategory, boolean open,
            Map<String, Object> fields, String gateKind, int gateEpoch, int eventSeq, String createdBy, long createdAtMs, long updatedAtMs,
            Long closedAtMs, int version) {
    }

    private static final RowMapper<Row> MAPPER = (rs, i) -> new Row(rs.getString("account_id"), rs.getString("workspace_id"),
            rs.getString("instance_no"), rs.getLong("definition_id"), rs.getString("process_code"), rs.getString("process_version"),
            rs.getString("subject_type"), rs.getString("subject_ref"), ProcessJson.readMap(rs.getString("subject_display")),
            rs.getString("status"), rs.getString("status_category"), rs.getInt("is_open") == 1, ProcessJson.readMap(rs.getString("fields")),
            rs.getString("gate_kind"), rs.getInt("gate_epoch"), rs.getInt("event_seq"), rs.getString("created_by"),
            rs.getLong("created_at_ms"), rs.getLong("updated_at_ms"), (Long) rs.getObject("closed_at_ms", Long.class), rs.getInt("version"));

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public InstanceDao(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    public void insert(WorkspaceId ws, Row r) {
        jdbc.sql("INSERT INTO proc_instance (account_id, workspace_id, instance_no, definition_id, process_code, process_version, subject_type, "
                        + "subject_ref, subject_display, status, status_category, is_open, fields, gate_kind, gate_epoch, event_seq, created_by, "
                        + "created_at_ms, updated_at_ms, closed_at_ms, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, " + dialect.jsonParam()
                        + ", ?, ?, ?, " + dialect.jsonParam() + ", ?, ?, ?, ?, ?, ?, ?, 0)")
                .params(r.accountId(), ws.value(), r.instanceNo(), r.definitionId(), r.processCode(), r.processVersion(), r.subjectType(),
                        r.subjectRef(), ProcessJson.write(r.subjectDisplay()), r.status(), r.statusCategory(), r.open() ? 1 : 0,
                        ProcessJson.write(r.fields()), r.gateKind(), r.gateEpoch(), r.eventSeq(), r.createdBy(), r.createdAtMs(),
                        r.updatedAtMs(), r.closedAtMs())
                .update();
    }

    public Optional<Row> find(WorkspaceId ws, String instanceNo) {
        return jdbc.sql("SELECT * FROM proc_instance WHERE workspace_id = ? AND instance_no = ?").params(ws.value(), instanceNo).query(MAPPER).optional();
    }

    /** 迁移：只有状态与版本都没被别人改过时才生效。 */
    public int transition(WorkspaceId ws, String instanceNo, String expectedStatus, int expectedVersion, String newStatus, String newCategory,
            boolean open, Map<String, Object> fields, String gateKind, long now, Long closedAt) {
        return jdbc.sql("UPDATE proc_instance SET status = ?, status_category = ?, is_open = ?, fields = " + dialect.jsonParam()
                        + ", gate_kind = ?, gate_epoch = gate_epoch + 1, event_seq = event_seq + 1, updated_at_ms = ?, closed_at_ms = ?, "
                        + "version = version + 1 WHERE workspace_id = ? AND instance_no = ? AND status = ? AND version = ?")
                .params(newStatus, newCategory, open ? 1 : 0, ProcessJson.write(fields), gateKind, now, closedAt, ws.value(), instanceNo,
                        expectedStatus, expectedVersion)
                .update();
    }

    /** 只改字段（不迁移）。 */
    public int updateFields(WorkspaceId ws, String instanceNo, int expectedVersion, Map<String, Object> fields, long now) {
        return jdbc.sql("UPDATE proc_instance SET fields = " + dialect.jsonParam() + ", updated_at_ms = ?, version = version + 1 "
                        + "WHERE workspace_id = ? AND instance_no = ? AND version = ? AND is_open = 1")
                .params(ProcessJson.write(fields), now, ws.value(), instanceNo, expectedVersion).update();
    }
}
