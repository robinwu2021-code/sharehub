package ai.neargo.opskit.process.store.dao;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.process.store.ProcessJson;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 审批单（proc_approval）与轮次（proc_approval_round）。一张审批单 = 一个实例上的一个审批门；
 * 驳回 / 撤回后再进入该门 = 同一张单的新一轮，前几轮的决定只增不改（21/02 §6.4）。
 */
public final class ApprovalDao {

    public record Row(String accountId, String workspaceId, String approvalNo, String instanceNo, String gateStatus, int currentRound,
            String currentStep, String status, String requestedBy, long requestedAtMs, Long finishedAtMs, int version) {
    }

    public record Round(String approvalNo, int roundNo, List<Object> plan, Map<String, Object> fieldsSnapshot, Map<String, Object> approversSnapshot,
            String submittedBy, long submittedAtMs, String result, Long finishedAtMs) {
    }

    private static final RowMapper<Row> MAPPER = (rs, i) -> new Row(rs.getString("account_id"), rs.getString("workspace_id"),
            rs.getString("approval_no"), rs.getString("instance_no"), rs.getString("gate_status"), rs.getInt("current_round"),
            rs.getString("current_step"), rs.getString("status"), rs.getString("requested_by"), rs.getLong("requested_at_ms"),
            (Long) rs.getObject("finished_at_ms", Long.class), rs.getInt("version"));

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public ApprovalDao(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    public Optional<Row> find(WorkspaceId ws, String approvalNo) {
        return jdbc.sql("SELECT * FROM proc_approval WHERE workspace_id = ? AND approval_no = ?").params(ws.value(), approvalNo).query(MAPPER).optional();
    }

    public Optional<Row> ofGate(WorkspaceId ws, String instanceNo, String gateStatus) {
        return jdbc.sql("SELECT * FROM proc_approval WHERE workspace_id = ? AND instance_no = ? AND gate_status = ?")
                .params(ws.value(), instanceNo, gateStatus).query(MAPPER).optional();
    }

    public List<Row> ofInstance(WorkspaceId ws, String instanceNo) {
        return jdbc.sql("SELECT * FROM proc_approval WHERE workspace_id = ? AND instance_no = ? ORDER BY requested_at_ms")
                .params(ws.value(), instanceNo).query(MAPPER).list();
    }

    /** 首轮：新建审批单。pending_key 唯一键保证同一个门同时至多一张待审。 */
    public void insert(WorkspaceId ws, Row r, String firstStep) {
        jdbc.sql("INSERT INTO proc_approval (account_id, workspace_id, approval_no, instance_no, gate_status, pending_key, current_round, current_step, "
                        + "status, requested_by, requested_at_ms, version) VALUES (?, ?, ?, ?, ?, ?, 1, ?, 'PENDING', ?, ?, 0)")
                .params(r.accountId(), ws.value(), r.approvalNo(), r.instanceNo(), r.gateStatus(), r.instanceNo() + ":" + r.gateStatus(),
                        firstStep, r.requestedBy(), r.requestedAtMs())
                .update();
    }

    /** 再次进入该门：开新一轮。只有已结束（非 PENDING）的审批单能开，0 行即并发冲突。 */
    public int reopen(WorkspaceId ws, String approvalNo, int expectedVersion, String firstStep, String by, long now) {
        return jdbc.sql("UPDATE proc_approval SET status = 'PENDING', pending_key = CONCAT(instance_no, ':', gate_status), current_round = current_round + 1, "
                        + "current_step = ?, requested_by = ?, requested_at_ms = ?, finished_at_ms = NULL, version = version + 1 "
                        + "WHERE workspace_id = ? AND approval_no = ? AND version = ? AND status <> 'PENDING'")
                .params(firstStep, by, now, ws.value(), approvalNo, expectedVersion).update();
    }

    public int moveToStep(WorkspaceId ws, String approvalNo, int expectedVersion, String step) {
        return jdbc.sql("UPDATE proc_approval SET current_step = ?, version = version + 1 WHERE workspace_id = ? AND approval_no = ? AND version = ? AND status = 'PENDING'")
                .params(step, ws.value(), approvalNo, expectedVersion).update();
    }

    /** 结束（APPROVED / REJECTED / WITHDRAWN）：清 pending_key 放开唯一键。 */
    public int finish(WorkspaceId ws, String approvalNo, int expectedVersion, String status, long now) {
        return jdbc.sql("UPDATE proc_approval SET status = ?, pending_key = NULL, current_step = NULL, finished_at_ms = ?, version = version + 1 "
                        + "WHERE workspace_id = ? AND approval_no = ? AND version = ? AND status = 'PENDING'")
                .params(status, now, ws.value(), approvalNo, expectedVersion).update();
    }

    public void insertRound(WorkspaceId ws, Round r) {
        jdbc.sql("INSERT INTO proc_approval_round (workspace_id, approval_no, round_no, plan, fields_snapshot, approvers_snapshot, submitted_by, submitted_at_ms) "
                        + "VALUES (?, ?, ?, " + dialect.jsonParam() + ", " + dialect.jsonParam() + ", " + dialect.jsonParam() + ", ?, ?)")
                .params(ws.value(), r.approvalNo(), r.roundNo(), ProcessJson.write(r.plan()), ProcessJson.write(r.fieldsSnapshot()),
                        ProcessJson.write(r.approversSnapshot()), r.submittedBy(), r.submittedAtMs())
                .update();
    }

    public void finishRound(WorkspaceId ws, String approvalNo, int roundNo, String result, long now) {
        jdbc.sql("UPDATE proc_approval_round SET result = ?, finished_at_ms = ? WHERE workspace_id = ? AND approval_no = ? AND round_no = ? AND result IS NULL")
                .params(result, now, ws.value(), approvalNo, roundNo).update();
    }

    public List<Round> rounds(WorkspaceId ws, String approvalNo) {
        return jdbc.sql("SELECT * FROM proc_approval_round WHERE workspace_id = ? AND approval_no = ? ORDER BY round_no")
                .params(ws.value(), approvalNo)
                .query((rs, i) -> new Round(rs.getString("approval_no"), rs.getInt("round_no"), ProcessJson.readList(rs.getString("plan")),
                        ProcessJson.readMap(rs.getString("fields_snapshot")), ProcessJson.readMap(rs.getString("approvers_snapshot")),
                        rs.getString("submitted_by"), rs.getLong("submitted_at_ms"), rs.getString("result"),
                        (Long) rs.getObject("finished_at_ms", Long.class)))
                .list();
    }

    /** 本轮全部审批任务（含已结束的）：判会签是否齐、跨步互斥、详情页展示。 */
    public List<TaskDao.Row> tasksOfRound(WorkspaceId ws, String approvalNo, int roundNo, TaskDao tasks) {
        return jdbc.sql("SELECT task_no FROM proc_task WHERE workspace_id = ? AND approval_no = ? AND round_no = ? ORDER BY created_at_ms, task_no")
                .params(ws.value(), approvalNo, roundNo).query(String.class).list().stream()
                .map(no -> tasks.find(ws, no).orElseThrow()).toList();
    }
}
