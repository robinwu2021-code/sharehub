package ai.neargo.opskit.process.store.dao;

import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.process.store.ProcessJson;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 列表读（21/07 O2-9）：我的待办 · 队列任务 · 队列计数 · 审批列表 · 审批计数。
 *
 * <p><b>计数与列表同口径</b>：每种计数都和对应列表共用同一段 WHERE（{@link Where}），不另写一份 —— 另写的那份迟早会漂。
 */
public final class QueryDao {

    /**
     * 操作人在查询里的样子。
     *
     * @param all        免可见性过滤（应用自身 / 持查看全部权限）
     * @param me         {@code member:x}；应用自身调用时为空
     * @param candidates 候选键（{@code member:x} ∪ 组 ∪ 角色 ∪ 宿主权限），也用作「是不是参与者」的主体集合
     * @param scopes     可见的范围标签
     */
    /** 数据范围不设限的标签：令牌带 {@code scopes:["*"]} 时，任何范围标签都算命中（只放开「范围」这一条，不放开候选 / 参与者 / 权限）。 */
    public static final String ANY_SCOPE = "*";

    /**
     * @param viewAllInScope 审批：持 approval:view:all —— 看得见范围内的全部审批（不必是候选 / 参与者），但仍受范围标签约束
     */
    public record Viewer(boolean all, String me, Collection<String> candidates, Collection<String> scopes, boolean viewAllInScope) {
        public Viewer {
            candidates = List.copyOf(candidates);
            scopes = List.copyOf(scopes);
        }

        public Viewer(boolean all, String me, Collection<String> candidates, Collection<String> scopes) {
            this(all, me, candidates, scopes, false);
        }
    }

    public record TodoRow(String taskNo, String kind, String origin, String instanceNo, String approvalNo, String title, Integer priorityOrd,
            String status, String assignee, Long dueAtMs, long createdAtMs, String queueGroup, String subjectType, String subjectRef,
            Map<String, Object> subjectDisplay, long definitionId) {
    }

    public record TaskRow(String taskNo, String kind, String instanceNo, String approvalNo, String queueGroup, String title, Integer priorityOrd,
            String status, String assignee, Long claimedAtMs, long createdAtMs, String subjectType, String subjectRef, Map<String, Object> subjectDisplay) {
    }

    public record QueueCountRow(String queueGroup, long unclaimed, long claimedByMe) {
    }

    public record ApprovalRow(String approvalNo, String instanceNo, String gateStatus, String status, int currentRound, String currentStep,
            String requestedBy, long requestedAtMs, Long finishedAtMs, Long stepStartedAtMs, Long stepDueAtMs, long definitionId, String processCode,
            String subjectType, String subjectRef, Map<String, Object> subjectDisplay) {
    }

    public record TodoCounts(long all, long process, long ticket, long claimable) {
    }

    /** 一段 WHERE 与它的参数；列表与计数共用。 */
    private static final class Where {
        /** FROM 子句；参数（若有）已按顺序放在 params 最前面。 */
        String from = TASK_FROM;
        final StringBuilder sql = new StringBuilder();
        final List<Object> params = new ArrayList<>();

        Where and(String clause, Object... ps) {
            sql.append(" AND ").append(clause);
            Collections.addAll(params, ps);
            return this;
        }

        Where andIn(String column, Collection<?> values) {
            sql.append(" AND ").append(column).append(" IN (").append(marks(values.size())).append(")");
            params.addAll(values);
            return this;
        }
    }

    private final JdbcClient jdbc;

    public QueryDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── 待办 ────────────────────────────────────────────────

    /**
     * @param origin    PROCESS / TICKET；空 = 全部
     * @param claimable true = 只看池里可领的；false = 只看在我名下的；空 = 两者
     */
    public List<TodoRow> todos(WorkspaceId ws, Viewer v, String origin, Boolean claimable, int offset, int limit) {
        Where w = todoWhere(ws, v, origin, claimable);
        List<Object> ps = new ArrayList<>(w.params);
        ps.add(limit);
        ps.add(offset);
        return jdbc.sql("SELECT t.task_no, t.kind, t.instance_no, t.approval_no, t.title, t.priority_ord, t.status, t.assignee, t.due_at_ms, "
                        + "t.created_at_ms, t.queue_group, i.subject_type, i.subject_ref, i.subject_display, i.definition_id, m.origin AS origin "
                        + w.from + " WHERE t.workspace_id = ?" + w.sql
                        + " ORDER BY CASE WHEN t.due_at_ms IS NULL THEN 1 ELSE 0 END, t.due_at_ms, t.created_at_ms, t.task_no LIMIT ? OFFSET ?")
                .params(ps)
                .query((rs, i) -> new TodoRow(rs.getString("task_no"), rs.getString("kind"), rs.getString("origin"), rs.getString("instance_no"),
                        rs.getString("approval_no"), rs.getString("title"), rs.getObject("priority_ord", Integer.class), rs.getString("status"),
                        rs.getString("assignee"), rs.getObject("due_at_ms", Long.class), rs.getLong("created_at_ms"), rs.getString("queue_group"),
                        rs.getString("subject_type"), rs.getString("subject_ref"), ProcessJson.readMap(rs.getString("subject_display")),
                        rs.getLong("definition_id")))
                .list();
    }

    public long countTodos(WorkspaceId ws, Viewer v, String origin, Boolean claimable) {
        Where w = todoWhere(ws, v, origin, claimable);
        // 包一层派生表：MariaDB 对「COUNT(*) 直接接 UNION 派生表 + 回表」会选一条慢得多的计划（O4-5 实测）
        return jdbc.sql("SELECT COUNT(*) FROM (SELECT t.task_no " + w.from + " WHERE t.workspace_id = ?" + w.sql + ") x").params(w.params)
                .query(Long.class).single();
    }

    /** 待办四个页签的角标，一次查完；和 {@link #todos} 同一段 WHERE。 */
    public TodoCounts todoCounts(WorkspaceId ws, Viewer v) {
        Where w = todoWhere(ws, v, null, null);
        return jdbc.sql("SELECT COUNT(*) AS a, "
                        + "COALESCE(SUM(CASE WHEN x.origin = 'PROCESS' THEN 1 ELSE 0 END), 0) AS p, "
                        + "COALESCE(SUM(CASE WHEN x.origin = 'TICKET' THEN 1 ELSE 0 END), 0) AS k, "
                        + "COALESCE(SUM(x.claimable), 0) AS c "
                        + "FROM (SELECT CASE WHEN " + CLAIMABLE + " THEN 1 ELSE 0 END AS claimable, m.origin AS origin " + w.from
                        + " WHERE t.workspace_id = ?" + w.sql + ") x")
                .params(w.params)
                .query((rs, i) -> new TodoCounts(rs.getLong("a"), rs.getLong("p"), rs.getLong("k"), rs.getLong("c"))).single();
    }

    /**
     * 我的待办 = （池里且我是候选 ∨ 在我名下）∧ 实例对我可见（21/02 §7.2 · §8.2）。
     *
     * <p>{@code claimable} 的口径（2026-09-28 与前端对齐）：<b>必须先领取才能办</b> —— 池里的办理任务（领了才能走迁移）与队列组任务。
     * 普通审批的候选任务可以直接决定，不算可领，它属于 claimable=false 那一页（「待我处理」）。
     */
    private Where todoWhere(WorkspaceId ws, Viewer v, String origin, Boolean claimable) {
        Where w = new Where();
        if (v.me() == null) {
            w.params.add(ws.value());
            return w.and("1 = 0"); // 应用自身没有「我的」待办
        }
        mineFrom(w, ws, v);
        w.params.add(ws.value());
        w.and("t.status IN ('OPEN','CLAIMED','ASSIGNED')");
        String viaCandidate = "(m.via = 'C' AND t.status = 'OPEN')";
        String viaAssignee = "(m.via = 'A' AND t.status <> 'OPEN')";
        if (Boolean.TRUE.equals(claimable)) {
            w.and(viaCandidate + " AND " + CLAIMABLE);
        } else if (Boolean.FALSE.equals(claimable)) {
            w.and("(" + viaAssignee + " OR (" + viaCandidate + " AND NOT " + CLAIMABLE + "))");
        } else {
            w.and("(" + viaCandidate + " OR " + viaAssignee + ")");
        }
        if (origin != null) {
            w.and("m.origin = ?", origin);
        }
        instanceVisible(w, v);
        return w;
    }

    /**
     * 「我的任务」集合，从我这一侧的索引出发（21/07 O4-5 实测：从空间内全部未结任务出发、逐行 EXISTS 判候选，
     * 10 万任务时 P95 已近 500 ms）。三路各走一条索引，再回表：
     * <ul>
     *   <li>C：开放候选直接命中我的候选键（idx_mine）；</li>
     *   <li>C：候选是参与者角色（participant:OWNER）而我担任该角色 —— 从我的参与记录出发（idx_principal → idx_inst_key）；</li>
     *   <li>A：在我名下（idx_assignee）。</li>
     * </ul>
     * 候选行在任务被别人领取后仍开放（结束才关），所以 C 路还要在外层要求任务仍是 OPEN；A 路要求非 OPEN —— 与改写前的口径逐条一致。
     * 来源（PROCESS / TICKET）记在候选行上，C 路顺手带出；A 路（在我名下，量小）按任务取。
     */
    private static void mineFrom(Where w, WorkspaceId ws, Viewer v) {
        StringBuilder m = new StringBuilder();
        if (!v.candidates().isEmpty()) {
            String in = marks(v.candidates().size());
            m.append("SELECT c.task_no, 'C' AS via, c.origin FROM proc_task_candidate c WHERE c.workspace_id = ? AND c.candidate_key IN (").append(in)
                    .append(") AND c.is_open = 1 UNION SELECT c.task_no, 'C' AS via, c.origin FROM proc_participant p JOIN proc_task_candidate c ")
                    .append("ON c.workspace_id = p.workspace_id AND c.instance_no = p.instance_no AND c.candidate_key = CONCAT('participant:', p.role) ")
                    .append("WHERE p.workspace_id = ? AND p.principal IN (").append(in).append(") AND c.is_open = 1 UNION ");
            w.params.add(ws.value());
            w.params.addAll(v.candidates());
            w.params.add(ws.value());
            w.params.addAll(v.candidates());
        }
        m.append("SELECT a.task_no, 'A' AS via, (SELECT MAX(c.origin) FROM proc_task_candidate c WHERE c.workspace_id = a.workspace_id "
                + "AND c.task_no = a.task_no) AS origin FROM proc_task a WHERE a.workspace_id = ? AND a.assignee = ? AND a.status IN ('CLAIMED','ASSIGNED')");
        w.params.add(ws.value());
        w.params.add(v.me());
        w.from = "FROM (" + m + ") m JOIN proc_task t ON t.workspace_id = ? AND t.task_no = m.task_no "
                + "JOIN proc_instance i ON i.workspace_id = t.workspace_id AND i.instance_no = t.instance_no";
        w.params.add(ws.value());
    }

    // ── 队列 / 任务 ─────────────────────────────────────────

    /**
     * 任务列表（队列页、我已领取）。能看到的 = 我是候选 ∨ 在我名下（应用自身不限）∧ 实例对我可见。
     *
     * @param statuses  任务状态（OPEN / CLAIMED / ASSIGNED / DONE / CANCELLED）；空 = 未结束的三种
     * @param claimed   true = 已被领取（CLAIMED）；false = 未被领取
     * @param assignee  {@code member:x}；空 = 不限
     */
    public List<TaskRow> tasks(WorkspaceId ws, Viewer v, String queueGroup, Collection<String> statuses, Boolean claimed, String assignee, String kind,
            int offset, int limit) {
        Where w = taskWhere(ws, v, queueGroup, statuses, claimed, assignee, kind);
        List<Object> ps = new ArrayList<>(w.params);
        ps.add(limit);
        ps.add(offset);
        return jdbc.sql("SELECT t.task_no, t.kind, t.instance_no, t.approval_no, t.queue_group, t.title, t.priority_ord, t.status, t.assignee, "
                        + "t.claimed_at_ms, t.created_at_ms, i.subject_type, i.subject_ref, i.subject_display " + TASK_FROM
                        + " WHERE t.workspace_id = ?" + w.sql
                        + " ORDER BY CASE WHEN t.priority_ord IS NULL THEN 1 ELSE 0 END, t.priority_ord, t.created_at_ms, t.task_no LIMIT ? OFFSET ?")
                .params(ps)
                .query((rs, i) -> new TaskRow(rs.getString("task_no"), rs.getString("kind"), rs.getString("instance_no"), rs.getString("approval_no"),
                        rs.getString("queue_group"), rs.getString("title"), rs.getObject("priority_ord", Integer.class), rs.getString("status"),
                        rs.getString("assignee"), rs.getObject("claimed_at_ms", Long.class), rs.getLong("created_at_ms"), rs.getString("subject_type"),
                        rs.getString("subject_ref"), ProcessJson.readMap(rs.getString("subject_display"))))
                .list();
    }

    public long countTasks(WorkspaceId ws, Viewer v, String queueGroup, Collection<String> statuses, Boolean claimed, String assignee, String kind) {
        Where w = taskWhere(ws, v, queueGroup, statuses, claimed, assignee, kind);
        return jdbc.sql("SELECT COUNT(*) " + TASK_FROM + " WHERE t.workspace_id = ?" + w.sql).params(w.params).query(Long.class).single();
    }

    /**
     * 每个队列组的待领数与我已领数。和 {@link #tasks} 同一个可见口径：待领 = tasks(group, [OPEN]) 的条数。
     *
     * @param groups 只统计这些组；空 = 全部有任务的队列组（应用自身）
     */
    public List<QueueCountRow> queueCounts(WorkspaceId ws, Viewer v, Collection<String> groups, String kind) {
        Where w = taskWhere(ws, v, null, List.of("OPEN", "CLAIMED"), null, null, kind);
        w.and("t.queue_group IS NOT NULL");
        if (groups != null) {
            if (groups.isEmpty()) {
                return List.of();
            }
            w.andIn("t.queue_group", groups);
        }
        List<Object> ps = new ArrayList<>();
        ps.add(v.me() == null ? "" : v.me());
        ps.addAll(w.params);
        return jdbc.sql("SELECT t.queue_group, COALESCE(SUM(CASE WHEN t.status = 'OPEN' THEN 1 ELSE 0 END), 0) AS unclaimed, "
                        + "COALESCE(SUM(CASE WHEN t.status = 'CLAIMED' AND t.assignee = ? THEN 1 ELSE 0 END), 0) AS mine "
                        + TASK_FROM + " WHERE t.workspace_id = ?" + w.sql + " GROUP BY t.queue_group ORDER BY t.queue_group")
                .params(ps)
                .query((rs, i) -> new QueueCountRow(rs.getString("queue_group"), rs.getLong("unclaimed"), rs.getLong("mine"))).list();
    }

    private Where taskWhere(WorkspaceId ws, Viewer v, String queueGroup, Collection<String> statuses, Boolean claimed, String assignee, String kind) {
        Where w = new Where();
        w.params.add(ws.value());
        if (kind != null) {
            w.and("t.kind = ?", kind);
        }
        if (statuses == null || statuses.isEmpty()) {
            w.and("t.status IN ('OPEN','CLAIMED','ASSIGNED')");
        } else {
            w.andIn("t.status", statuses);
        }
        if (queueGroup != null) {
            w.and("t.queue_group = ?", queueGroup);
        }
        if (Boolean.TRUE.equals(claimed)) {
            w.and("t.status = 'CLAIMED'");
        } else if (Boolean.FALSE.equals(claimed)) {
            w.and("t.status <> 'CLAIMED'");
        }
        if (assignee != null) {
            w.and("t.assignee = ?", assignee);
        }
        if (!v.all()) {
            if (v.me() == null) {
                return w.and("1 = 0");
            }
            w.sql.append(" AND (");
            candidateMatch(w, v);
            w.sql.append(" OR t.assignee = ?)");
            w.params.add(v.me());
            instanceVisible(w, v);
        }
        return w;
    }

    // ── 审批 ────────────────────────────────────────────────

    public List<ApprovalRow> approvals(WorkspaceId ws, Viewer v, Collection<String> statuses, String processCode, String requestedBy, Long fromMs,
            String keyword, int offset, int limit) {
        Where w = approvalWhere(ws, v, statuses, processCode, requestedBy, fromMs, keyword);
        List<Object> ps = new ArrayList<>(w.params);
        ps.add(limit);
        ps.add(offset);
        return jdbc.sql("SELECT a.approval_no, a.instance_no, a.gate_status, a.status, a.current_round, a.current_step, a.requested_by, "
                        + "a.requested_at_ms, a.finished_at_ms, i.definition_id, i.process_code, i.subject_type, i.subject_ref, i.subject_display, "
                        + "(SELECT MIN(s.created_at_ms) FROM proc_task s WHERE s.workspace_id = a.workspace_id AND s.approval_no = a.approval_no "
                        + "AND s.round_no = a.current_round AND s.step_code = a.current_step) AS step_started_at_ms, "
                        + "(SELECT MIN(s.due_at_ms) FROM proc_task s WHERE s.workspace_id = a.workspace_id AND s.approval_no = a.approval_no "
                        + "AND s.round_no = a.current_round AND s.step_code = a.current_step) AS step_due_at_ms "
                        + APPROVAL_FROM + " WHERE a.workspace_id = ?" + w.sql + " ORDER BY a.requested_at_ms DESC, a.approval_no DESC LIMIT ? OFFSET ?")
                .params(ps)
                .query(APPROVAL_MAPPER).list();
    }

    public long countApprovals(WorkspaceId ws, Viewer v, Collection<String> statuses, String processCode, String requestedBy, Long fromMs,
            String keyword) {
        Where w = approvalWhere(ws, v, statuses, processCode, requestedBy, fromMs, keyword);
        return jdbc.sql("SELECT COUNT(*) " + APPROVAL_FROM + " WHERE a.workspace_id = ?" + w.sql).params(w.params).query(Long.class).single();
    }

    /** 按状态分组计数（状态页签角标）：与 {@link #approvals} 同一段 WHERE，只是不按状态筛。 */
    public Map<String, Long> approvalCountsByStatus(WorkspaceId ws, Viewer v, String processCode, String requestedBy, Long fromMs, String keyword) {
        Where w = approvalWhere(ws, v, null, processCode, requestedBy, fromMs, keyword);
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("SELECT a.status, COUNT(*) AS n " + APPROVAL_FROM + " WHERE a.workspace_id = ?" + w.sql + " GROUP BY a.status")
                .params(w.params).query((rs, i) -> out.put(rs.getString("status"), rs.getLong("n"))).list();
        return out;
    }

    /**
     * 审批的可见 = 应用自身 ∨ 我是提交人 ∨ 我是实例参与者 ∨（（持 approval:view:all ∨ 我是（或曾是）某个审批任务的候选或处理人）∧ 实例范围对我可见）。
     * approval:view:all 是「范围内的全部」，不越过数据范围（2026-09-28 与宿主的角色映射对齐；全量范围的人带 scopes:["*"]）。
     * 比实例可见严：没配范围标签的实例对全空间可见，但审批单不能因此对全空间公开；与 ApprovalAppService.canSee 同一口径。
     */
    private Where approvalWhere(WorkspaceId ws, Viewer v, Collection<String> statuses, String processCode, String requestedBy, Long fromMs,
            String keyword) {
        Where w = new Where();
        w.params.add(ws.value());
        if (statuses != null && !statuses.isEmpty()) {
            w.andIn("a.status", statuses);
        }
        if (processCode != null) {
            w.and("i.process_code = ?", processCode);
        }
        if (requestedBy != null) {
            w.and("a.requested_by = ?", requestedBy);
        }
        if (fromMs != null) {
            w.and("a.requested_at_ms >= ?", fromMs);
        }
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.strip();
            w.and("(a.approval_no = ? OR a.instance_no = ? OR i.subject_ref LIKE ?)", k, k, "%" + escapeLike(k) + "%");
        }
        if (!v.all()) {
            if (v.me() == null || v.candidates().isEmpty()) {
                return w.and("1 = 0");
            }
            String in = marks(v.candidates().size());
            // 提交人 ∨ 参与者：不受范围限制
            w.sql.append(" AND (a.requested_by = ?");
            w.params.add(v.me());
            w.sql.append(" OR EXISTS (SELECT 1 FROM proc_participant p WHERE p.workspace_id = a.workspace_id AND p.instance_no = a.instance_no AND p.principal IN (")
                    .append(in).append("))");
            w.params.addAll(v.candidates());
            // 审批任务的候选 / 处理人，或持 approval:view:all：还要实例范围对我可见
            w.sql.append(" OR ((" + (v.viewAllInScope() ? "1 = 1 OR " : "")
                    + "EXISTS (SELECT 1 FROM proc_task s WHERE s.workspace_id = a.workspace_id AND s.approval_no = a.approval_no AND (s.completed_by = ?")
                    .append(" OR s.assignee = ?")
                    .append(" OR EXISTS (SELECT 1 FROM proc_task_candidate c WHERE c.workspace_id = s.workspace_id AND c.task_no = s.task_no AND c.candidate_key IN (")
                    .append(in).append(")))))");
            w.params.add(v.me());
            w.params.add(v.me());
            w.params.addAll(v.candidates());
            w.sql.append(" AND (NOT EXISTS (SELECT 1 FROM proc_instance_scope sc WHERE sc.workspace_id = a.workspace_id AND sc.instance_no = a.instance_no)");
            if (v.scopes().contains(ANY_SCOPE)) {
                w.sql.append(" OR 1 = 1"); // 数据范围不设限（令牌 scopes:["*"]）
            } else if (!v.scopes().isEmpty()) {
                w.sql.append(" OR EXISTS (SELECT 1 FROM proc_instance_scope sc WHERE sc.workspace_id = a.workspace_id AND sc.instance_no = a.instance_no AND sc.scope_tag IN (")
                        .append(marks(v.scopes().size())).append("))");
                w.params.addAll(v.scopes());
            }
            w.sql.append(")))");
        }
        return w;
    }

    // ── 实例 / 对账 ─────────────────────────────────────────

    public record InstanceRow(String instanceNo, long definitionId, String processCode, String processVersion, String subjectType, String subjectRef,
            Map<String, Object> subjectDisplay, String status, String statusCategory, boolean open, String createdBy, long createdAtMs,
            long updatedAtMs, Long closedAtMs) {
    }

    /** 实例筛选；各项为空 = 不限。 */
    public record InstanceFilter(String instanceNo, String processCode, Collection<String> statusCategories, Collection<String> statuses, Boolean open,
            String subjectType, String subjectRef, Long createdFromMs, Long createdToMs) {
    }

    public List<InstanceRow> instances(WorkspaceId ws, Viewer v, InstanceFilter f, int offset, int limit) {
        Where w = instanceWhere(ws, v, f);
        List<Object> ps = new ArrayList<>(w.params);
        ps.add(limit);
        ps.add(offset);
        return jdbc.sql("SELECT i.* FROM proc_instance i WHERE i.workspace_id = ?" + w.sql
                        + " ORDER BY i.created_at_ms DESC, i.instance_no DESC LIMIT ? OFFSET ?")
                .params(ps)
                .query((rs, n) -> new InstanceRow(rs.getString("instance_no"), rs.getLong("definition_id"), rs.getString("process_code"),
                        rs.getString("process_version"), rs.getString("subject_type"), rs.getString("subject_ref"),
                        ProcessJson.readMap(rs.getString("subject_display")), rs.getString("status"), rs.getString("status_category"),
                        rs.getInt("is_open") == 1, rs.getString("created_by"), rs.getLong("created_at_ms"), rs.getLong("updated_at_ms"),
                        rs.getObject("closed_at_ms", Long.class)))
                .list();
    }

    public long countInstances(WorkspaceId ws, Viewer v, InstanceFilter f) {
        Where w = instanceWhere(ws, v, f);
        return jdbc.sql("SELECT COUNT(*) FROM proc_instance i WHERE i.workspace_id = ?" + w.sql).params(w.params).query(Long.class).single();
    }

    /**
     * 实例的「读」可见（历史、实例查询、对账共用；与审批可见同一口径）：应用自身 ∨ 参与者
     * ∨（办理 / 审批任务的候选或处理人 ∧ 实例范围可见）。没配范围标签的实例也不因此对全空间公开。
     */
    private Where instanceWhere(WorkspaceId ws, Viewer v, InstanceFilter f) {
        Where w = new Where();
        w.params.add(ws.value());
        if (f.instanceNo() != null) {
            w.and("i.instance_no = ?", f.instanceNo());
        }
        if (f.processCode() != null) {
            w.and("i.process_code = ?", f.processCode());
        }
        if (f.statusCategories() != null && !f.statusCategories().isEmpty()) {
            w.andIn("i.status_category", f.statusCategories());
        }
        if (f.statuses() != null && !f.statuses().isEmpty()) {
            w.andIn("i.status", f.statuses());
        }
        if (f.open() != null) {
            w.and("i.is_open = ?", f.open() ? 1 : 0);
        }
        if (f.subjectType() != null) {
            w.and("i.subject_type = ?", f.subjectType());
        }
        if (f.subjectRef() != null) {
            w.and("i.subject_ref = ?", f.subjectRef());
        }
        if (f.createdFromMs() != null) {
            w.and("i.created_at_ms >= ?", f.createdFromMs());
        }
        if (f.createdToMs() != null) {
            w.and("i.created_at_ms < ?", f.createdToMs());
        }
        if (!v.all()) {
            if (v.me() == null || v.candidates().isEmpty()) {
                return w.and("1 = 0");
            }
            String in = marks(v.candidates().size());
            w.sql.append(" AND (EXISTS (SELECT 1 FROM proc_participant p WHERE p.workspace_id = i.workspace_id AND p.instance_no = i.instance_no AND p.principal IN (")
                    .append(in).append("))");
            w.params.addAll(v.candidates());
            w.sql.append(" OR (EXISTS (SELECT 1 FROM proc_task s WHERE s.workspace_id = i.workspace_id AND s.instance_no = i.instance_no AND (s.completed_by = ?")
                    .append(" OR s.assignee = ?")
                    .append(" OR EXISTS (SELECT 1 FROM proc_task_candidate c WHERE c.workspace_id = s.workspace_id AND c.task_no = s.task_no AND c.candidate_key IN (")
                    .append(in).append("))))");
            w.params.add(v.me());
            w.params.add(v.me());
            w.params.addAll(v.candidates());
            w.sql.append(" AND (NOT EXISTS (SELECT 1 FROM proc_instance_scope sc WHERE sc.workspace_id = i.workspace_id AND sc.instance_no = i.instance_no)");
            if (v.scopes().contains(ANY_SCOPE)) {
                w.sql.append(" OR 1 = 1"); // 数据范围不设限（令牌 scopes:["*"]）
            } else if (!v.scopes().isEmpty()) {
                w.sql.append(" OR EXISTS (SELECT 1 FROM proc_instance_scope sc WHERE sc.workspace_id = i.workspace_id AND sc.instance_no = i.instance_no AND sc.scope_tag IN (")
                        .append(marks(v.scopes().size())).append("))");
                w.params.addAll(v.scopes());
            }
            w.sql.append(")))");
        }
        return w;
    }

    public List<ApprovalRow> approvalsOfInstances(WorkspaceId ws, Collection<String> instanceNos) {
        if (instanceNos.isEmpty()) {
            return List.of();
        }
        List<Object> ps = new ArrayList<>();
        ps.add(ws.value());
        ps.addAll(instanceNos);
        return jdbc.sql("SELECT a.approval_no, a.instance_no, a.gate_status, a.status, a.current_round, a.current_step, a.requested_by, "
                        + "a.requested_at_ms, a.finished_at_ms, i.definition_id, i.process_code, i.subject_type, i.subject_ref, i.subject_display "
                        + APPROVAL_FROM + " WHERE a.workspace_id = ? AND a.instance_no IN ("
                        + marks(instanceNos.size()) + ") ORDER BY a.requested_at_ms, a.approval_no")
                // 对账不需要「当前步」时刻；不查这两列（无类型的 NULL 在 PG 是 text、CAST AS BIGINT 在 MySQL 系不认）
                .params(ps).query((rs, n) -> new ApprovalRow(rs.getString("approval_no"), rs.getString("instance_no"), rs.getString("gate_status"),
                        rs.getString("status"), rs.getInt("current_round"), rs.getString("current_step"), rs.getString("requested_by"),
                        rs.getLong("requested_at_ms"), rs.getObject("finished_at_ms", Long.class), null, null, rs.getLong("definition_id"),
                        rs.getString("process_code"), rs.getString("subject_type"), rs.getString("subject_ref"),
                        ProcessJson.readMap(rs.getString("subject_display"))))
                .list();
    }

    // ── 流程统计 ────────────────────────────────────────────

    /** @param closedCount 已关闭数；@param closedDurationMs 已关闭实例的「创建 → 关闭」时长之和（平均 = 和 / 数） */
    public record CategoryStat(String processCode, String statusCategory, long count, long closedCount, long closedDurationMs) {
    }

    public record ApprovalStat(String processCode, String status, long count) {
    }

    /** 按流程 × 状态类别计数（创建时间落在区间内的实例）。聚合，不按人过滤 —— 调用方先判 report:view。 */
    public List<CategoryStat> instanceStats(WorkspaceId ws, String processCode, Long fromMs, Long toMs) {
        Where w = statWhere(ws, "i", processCode, fromMs, toMs);
        return jdbc.sql("SELECT i.process_code, i.status_category, COUNT(*) AS n, "
                        + "COALESCE(SUM(CASE WHEN i.closed_at_ms IS NOT NULL THEN 1 ELSE 0 END), 0) AS closed_n, "
                        + "COALESCE(SUM(CASE WHEN i.closed_at_ms IS NOT NULL THEN i.closed_at_ms - i.created_at_ms ELSE 0 END), 0) AS closed_ms "
                        + "FROM proc_instance i WHERE i.workspace_id = ?" + w.sql + " GROUP BY i.process_code, i.status_category ORDER BY i.process_code, i.status_category")
                .params(w.params)
                .query((rs, n) -> new CategoryStat(rs.getString("process_code"), rs.getString("status_category"), rs.getLong("n"), rs.getLong("closed_n"),
                        rs.getLong("closed_ms")))
                .list();
    }

    public List<ApprovalStat> approvalStats(WorkspaceId ws, String processCode, Long fromMs, Long toMs) {
        Where w = statWhere(ws, "i", processCode, fromMs, toMs);
        return jdbc.sql("SELECT i.process_code, a.status, COUNT(*) AS n " + APPROVAL_FROM + " WHERE a.workspace_id = ?" + w.sql
                        + " GROUP BY i.process_code, a.status ORDER BY i.process_code, a.status")
                .params(w.params)
                .query((rs, n) -> new ApprovalStat(rs.getString("process_code"), rs.getString("status"), rs.getLong("n"))).list();
    }

    private static Where statWhere(WorkspaceId ws, String alias, String processCode, Long fromMs, Long toMs) {
        Where w = new Where();
        w.params.add(ws.value());
        if (processCode != null) {
            w.and(alias + ".process_code = ?", processCode);
        }
        if (fromMs != null) {
            w.and(alias + ".created_at_ms >= ?", fromMs);
        }
        if (toMs != null) {
            w.and(alias + ".created_at_ms < ?", toMs);
        }
        return w;
    }

    // ── 定义统计 ────────────────────────────────────────────

    /** 某流程在途（未关闭）的实例数；{@code notOnDefinition} 非空时只数不在该定义行上的（= 还按旧版本走的）。 */
    public long openInstances(WorkspaceId ws, String processCode, Long notOnDefinition) {
        String sql = "SELECT COUNT(*) FROM proc_instance WHERE workspace_id = ? AND process_code = ? AND is_open = 1"
                + (notOnDefinition == null ? "" : " AND definition_id <> ?");
        List<Object> ps = new ArrayList<>(List.of(ws.value(), processCode));
        if (notOnDefinition != null) {
            ps.add(notOnDefinition);
        }
        return jdbc.sql(sql).params(ps).query(Long.class).single();
    }

    // ── 共用片段 ────────────────────────────────────────────

    /** 必须先领取才能办：池里（OPEN）的办理任务，或队列组任务。与 ProcessQueryService.claimable 同口径。 */
    private static final String CLAIMABLE = "(t.status = 'OPEN' AND (t.kind = 'WORK' OR t.queue_group IS NOT NULL))";
    private static final String TASK_FROM = "FROM proc_task t JOIN proc_instance i ON i.workspace_id = t.workspace_id AND i.instance_no = t.instance_no";
    private static final String APPROVAL_FROM = "FROM proc_approval a JOIN proc_instance i ON i.workspace_id = a.workspace_id AND i.instance_no = a.instance_no";
    /** 待办来源记在候选行上（由建任务的调用方给出）；一条任务的所有候选行来源相同。 */
    private static final String ORIGIN = "(SELECT MAX(c.origin) FROM proc_task_candidate c WHERE c.workspace_id = t.workspace_id AND c.task_no = t.task_no)";

    private static final RowMapper<ApprovalRow> APPROVAL_MAPPER = (rs, i) -> new ApprovalRow(rs.getString("approval_no"), rs.getString("instance_no"),
            rs.getString("gate_status"), rs.getString("status"), rs.getInt("current_round"), rs.getString("current_step"), rs.getString("requested_by"),
            rs.getLong("requested_at_ms"), rs.getObject("finished_at_ms", Long.class), rs.getObject("step_started_at_ms", Long.class),
            rs.getObject("step_due_at_ms", Long.class), rs.getLong("definition_id"), rs.getString("process_code"), rs.getString("subject_type"),
            rs.getString("subject_ref"), ProcessJson.readMap(rs.getString("subject_display")));

    /** 我是这条任务的开放候选：直接命中候选键，或候选是参与者角色（participant:OWNER）而我担任该角色。 */
    private static void candidateMatch(Where w, Viewer v) {
        if (v.candidates().isEmpty()) {
            w.sql.append("1 = 0");
            return;
        }
        String in = marks(v.candidates().size());
        w.sql.append("EXISTS (SELECT 1 FROM proc_task_candidate c WHERE c.workspace_id = t.workspace_id AND c.task_no = t.task_no AND c.is_open = 1 AND (c.candidate_key IN (")
                .append(in)
                .append(") OR EXISTS (SELECT 1 FROM proc_participant p WHERE p.workspace_id = c.workspace_id AND p.instance_no = c.instance_no ")
                .append("AND CONCAT('participant:', p.role) = c.candidate_key AND p.principal IN (").append(in).append("))))");
        w.params.addAll(v.candidates());
        w.params.addAll(v.candidates());
    }

    /** 实例可见（21/02 §7.2，流程层）：未打范围标签 ∨ 标签相交 ∨ 我是参与者。 */
    private static void instanceVisible(Where w, Viewer v) {
        if (v.all()) {
            return;
        }
        w.sql.append(" AND (NOT EXISTS (SELECT 1 FROM proc_instance_scope s WHERE s.workspace_id = i.workspace_id AND s.instance_no = i.instance_no)");
        if (v.scopes().contains(ANY_SCOPE)) {
                w.sql.append(" OR 1 = 1"); // 数据范围不设限（令牌 scopes:["*"]）
            } else if (!v.scopes().isEmpty()) {
            w.sql.append(" OR EXISTS (SELECT 1 FROM proc_instance_scope s WHERE s.workspace_id = i.workspace_id AND s.instance_no = i.instance_no AND s.scope_tag IN (")
                    .append(marks(v.scopes().size())).append("))");
            w.params.addAll(v.scopes());
        }
        if (!v.candidates().isEmpty()) {
            w.sql.append(" OR EXISTS (SELECT 1 FROM proc_participant p WHERE p.workspace_id = i.workspace_id AND p.instance_no = i.instance_no AND p.principal IN (")
                    .append(marks(v.candidates().size())).append("))");
            w.params.addAll(v.candidates());
        }
        w.sql.append(")");
    }

    private static String marks(int n) {
        return String.join(", ", Collections.nCopies(n, "?"));
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
