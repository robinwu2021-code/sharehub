package ai.neargo.opskit.process.store.dao;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 人工任务（proc_task）与候选（proc_task_candidate）。所有状态变化都是条件更新，0 行即并发冲突。
 *
 * <p>待办口径：可领取 = 候选匹配且任务 OPEN；我在办 = assignee 是我且 CLAIMED / ASSIGNED。领取与放回不改候选行，
 * 候选行只在任务结束（DONE / CANCELLED）时关闭 —— 放回后原候选人自然又看得见。
 */
public final class TaskDao {

    public record Row(String accountId, String workspaceId, String taskNo, String kind, String instanceNo, String approvalNo, Integer roundNo,
            String stepCode, String queueGroup, String title, Integer priorityOrd, String status, String assignee, Long claimedAtMs,
            int claimEpoch, String decision, String reason, String completedBy, Long completedAtMs, Long dueAtMs, int dueEpoch,
            long createdAtMs, long updatedAtMs, int version) {
    }

    private static final RowMapper<Row> MAPPER = (rs, i) -> new Row(rs.getString("account_id"), rs.getString("workspace_id"),
            rs.getString("task_no"), rs.getString("kind"), rs.getString("instance_no"), rs.getString("approval_no"),
            (Integer) rs.getObject("round_no", Integer.class), rs.getString("step_code"), rs.getString("queue_group"), rs.getString("title"),
            (Integer) rs.getObject("priority_ord", Integer.class), rs.getString("status"), rs.getString("assignee"),
            (Long) rs.getObject("claimed_at_ms", Long.class), rs.getInt("claim_epoch"), rs.getString("decision"), rs.getString("reason"),
            rs.getString("completed_by"), (Long) rs.getObject("completed_at_ms", Long.class), (Long) rs.getObject("due_at_ms", Long.class),
            rs.getInt("due_epoch"), rs.getLong("created_at_ms"), rs.getLong("updated_at_ms"), rs.getInt("version"));

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public TaskDao(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    public void insert(WorkspaceId ws, Row r) {
        jdbc.sql("INSERT INTO proc_task (account_id, workspace_id, task_no, kind, instance_no, approval_no, round_no, step_code, queue_group, title, "
                        + "priority_ord, status, assignee, claimed_at_ms, claim_epoch, due_at_ms, due_epoch, created_at_ms, updated_at_ms, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 0, ?, ?, 0)")
                .params(r.accountId(), ws.value(), r.taskNo(), r.kind(), r.instanceNo(), r.approvalNo(), r.roundNo(), r.stepCode(), r.queueGroup(),
                        r.title(), r.priorityOrd(), r.status(), r.assignee(), r.claimedAtMs(), r.dueAtMs(), r.createdAtMs(), r.updatedAtMs())
                .update();
    }

    public Optional<Row> find(WorkspaceId ws, String taskNo) {
        return jdbc.sql("SELECT * FROM proc_task WHERE workspace_id = ? AND task_no = ?").params(ws.value(), taskNo).query(MAPPER).optional();
    }

    public List<Row> unfinishedOfInstance(WorkspaceId ws, String instanceNo, String kind) {
        return jdbc.sql("SELECT * FROM proc_task WHERE workspace_id = ? AND instance_no = ? AND kind = ? AND status IN ('OPEN','CLAIMED','ASSIGNED') ORDER BY created_at_ms")
                .params(ws.value(), instanceNo, kind).query(MAPPER).list();
    }

    /** 领取：只有仍是 OPEN 时成功（并发领取只一人赢）。 */
    public int claim(WorkspaceId ws, String taskNo, String assignee, long now) {
        return jdbc.sql("UPDATE proc_task SET status = 'CLAIMED', assignee = ?, claimed_at_ms = ?, claim_epoch = claim_epoch + 1, updated_at_ms = ?, "
                        + "version = version + 1 WHERE workspace_id = ? AND task_no = ? AND status = 'OPEN'")
                .params(assignee, now, now, ws.value(), taskNo).update();
    }

    /** 放回：只有领取人本人、且仍是 CLAIMED 时成功。expectedEpoch 非空时还要求领取代次没变（领取超时用）。 */
    public int release(WorkspaceId ws, String taskNo, String assignee, Integer expectedEpoch, long now) {
        return jdbc.sql("UPDATE proc_task SET status = 'OPEN', assignee = NULL, claimed_at_ms = NULL, claim_epoch = claim_epoch + 1, updated_at_ms = ?, "
                        + "version = version + 1 WHERE workspace_id = ? AND task_no = ? AND status = 'CLAIMED' AND assignee = ?"
                        + (expectedEpoch == null ? "" : " AND claim_epoch = " + expectedEpoch.intValue()))
                .params(now, ws.value(), taskNo, assignee).update();
    }

    /** 指派 / 转派：未结束的任务直接交给某人。 */
    public int assign(WorkspaceId ws, String taskNo, int expectedVersion, String assignee, long now) {
        return jdbc.sql("UPDATE proc_task SET status = 'ASSIGNED', assignee = ?, claimed_at_ms = NULL, claim_epoch = claim_epoch + 1, updated_at_ms = ?, "
                        + "version = version + 1 WHERE workspace_id = ? AND task_no = ? AND version = ? AND status IN ('OPEN','CLAIMED','ASSIGNED')")
                .params(assignee, now, ws.value(), taskNo, expectedVersion).update();
    }

    /** 退回池：去掉办理人，重新可领取。 */
    public int pool(WorkspaceId ws, String taskNo, int expectedVersion, long now) {
        return jdbc.sql("UPDATE proc_task SET status = 'OPEN', assignee = NULL, claimed_at_ms = NULL, claim_epoch = claim_epoch + 1, updated_at_ms = ?, "
                        + "version = version + 1 WHERE workspace_id = ? AND task_no = ? AND version = ? AND status IN ('CLAIMED','ASSIGNED')")
                .params(now, ws.value(), taskNo, expectedVersion).update();
    }

    /** 结束（DONE / CANCELLED），并关闭候选行。 */
    public int finish(WorkspaceId ws, String taskNo, int expectedVersion, String status, String decision, String reason, String by, long now) {
        int n = jdbc.sql("UPDATE proc_task SET status = ?, decision = ?, reason = ?, completed_by = ?, completed_at_ms = ?, updated_at_ms = ?, "
                        + "version = version + 1 WHERE workspace_id = ? AND task_no = ? AND version = ? AND status IN ('OPEN','CLAIMED','ASSIGNED')")
                .params(status, decision, reason, by, now, now, ws.value(), taskNo, expectedVersion).update();
        if (n == 1) {
            jdbc.sql("UPDATE proc_task_candidate SET is_open = 0 WHERE workspace_id = ? AND task_no = ?").params(ws.value(), taskNo).update();
        }
        return n;
    }

    public void addCandidates(WorkspaceId ws, String taskNo, String instanceNo, String kind, String origin, Integer priorityOrd, Long dueAtMs,
            Collection<String> keys, long now) {
        String sql = dialect.upsert("proc_task_candidate", List.of("workspace_id", "task_no", "candidate_key"),
                List.of("workspace_id", "task_no", "instance_no", "candidate_key", "is_open", "task_kind", "origin", "priority_ord", "due_at_ms", "created_at_ms"),
                List.of());
        for (String k : keys) {
            jdbc.sql(sql).params(ws.value(), taskNo, instanceNo, k, 1, kind, origin, priorityOrd, dueAtMs, now).update();
        }
    }

    /** 各办理人名下未结的办理任务数（最少负载派单用）；没有任务的成员不在结果里。 */
    public java.util.Map<String, Long> openWorkByAssignee(WorkspaceId ws, java.util.Collection<String> assignees) {
        if (assignees.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        jdbc.sql("SELECT assignee, COUNT(*) AS n FROM proc_task WHERE workspace_id = :ws AND kind = 'WORK' AND status IN ('CLAIMED','ASSIGNED') "
                        + "AND assignee IN (:who) GROUP BY assignee")
                .param("ws", ws.value()).param("who", assignees)
                .query((rs, i) -> out.put(rs.getString("assignee"), rs.getLong("n"))).list();
        return out;
    }

    public Set<String> candidates(WorkspaceId ws, String taskNo) {
        return Set.copyOf(jdbc.sql("SELECT candidate_key FROM proc_task_candidate WHERE workspace_id = ? AND task_no = ?")
                .params(ws.value(), taskNo).query(String.class).list());
    }

    /** 领取超时兜底扫描用：领取早于 before 仍未办完的。跨空间是有意为之，第一个参数显式写 UNKNOWN 让它在调用处显形。 */
    public List<Row> claimedBefore(ai.neargo.opskit.commons.tenancy.AccountId unknown, long before, int limit) {
        if (!ai.neargo.opskit.commons.tenancy.AccountId.UNKNOWN.equals(unknown)) {
            throw new IllegalArgumentException("claim-timeout scan is cross-tenant by design");
        }
        return jdbc.sql("SELECT * FROM proc_task WHERE status = 'CLAIMED' AND claimed_at_ms < ? ORDER BY claimed_at_ms" + dialect.limitOffset(limit, 0))
                .param(before).query(MAPPER).list();
    }
}
