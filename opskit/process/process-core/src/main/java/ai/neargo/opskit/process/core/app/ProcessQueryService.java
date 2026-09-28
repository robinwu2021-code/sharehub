package ai.neargo.opskit.process.core.app;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.CandidateKeys;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.commons.query.Page;
import ai.neargo.opskit.commons.query.PageRequest;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.process.api.definition.ApprovalStepDef;
import ai.neargo.opskit.process.api.definition.CompiledProcess;
import ai.neargo.opskit.process.api.definition.GateDef;
import ai.neargo.opskit.process.api.model.SubjectRef;
import ai.neargo.opskit.process.api.model.query.ApprovalDetail;
import ai.neargo.opskit.process.api.model.query.ApprovalQuery;
import ai.neargo.opskit.process.api.model.query.ApprovalRow;
import ai.neargo.opskit.process.api.model.query.QueueCount;
import ai.neargo.opskit.process.api.model.query.QueueTask;
import ai.neargo.opskit.process.api.model.query.TaskQuery;
import ai.neargo.opskit.process.api.model.query.TodoCount;
import ai.neargo.opskit.process.api.model.query.TodoItem;
import ai.neargo.opskit.process.api.model.query.TodoQuery;
import ai.neargo.opskit.process.core.port.Directory;
import ai.neargo.opskit.process.api.model.query.SlaSummary;
import ai.neargo.opskit.process.store.dao.QueryDao;
import ai.neargo.opskit.process.store.dao.SlaClockDao;
import ai.neargo.opskit.process.store.dao.QueryDao.Viewer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 列表读（21/07 O2-9）：我的待办 · 任务 / 队列 · 审批列表与计数。只读，不开事务。
 *
 * <p>三种可见口径（21/02 §7.2）：
 * <ul>
 *   <li>待办 = 候选匹配 ∧ 实例可见；应用自身没有「我的」待办。</li>
 *   <li>任务 = （候选 ∨ 在我名下）∧ 实例可见；应用自身不限。</li>
 *   <li>审批 = 提交人 ∨ 参与者 ∨ 审批任务的候选 / 处理人；持 {@code approval:view:all} 或应用自身不限。</li>
 * </ul>
 */
public class ProcessQueryService {

    public static final String APPROVAL_VIEW_ALL = "approval:view:all";
    private static final Set<String> ORIGINS = Set.of("ALL", "PROCESS", "TICKET");
    private static final Set<String> TASK_STATUSES = Set.of("OPEN", "CLAIMED", "ASSIGNED", "DONE", "CANCELLED");
    private static final Set<String> APPROVAL_STATUSES = Set.of("PENDING", "APPROVED", "REJECTED", "WITHDRAWN");

    private final ProcessDefinitionAppService definitions;
    private final QueryDao queries;
    private final Directory directory;
    private final SlaClockDao slaClocks;

    public ProcessQueryService(ProcessDefinitionAppService definitions, QueryDao queries, Directory directory) {
        this(definitions, queries, directory, null);
    }

    /** @param slaClocks 为空时列表不带 SLA 摘要 */
    public ProcessQueryService(ProcessDefinitionAppService definitions, QueryDao queries, Directory directory, SlaClockDao slaClocks) {
        this.definitions = definitions;
        this.queries = queries;
        this.directory = directory;
        this.slaClocks = slaClocks;
    }

    // ── 待办 ────────────────────────────────────────────────

    @SystemAction("只读：结果按操作人的候选键与可见范围过滤，本身即权限边界")
    public Page<TodoItem> todos(Actor actor, TodoQuery q) {
        PageRequest page = PageRequest.of(q.page(), q.size());
        String origin = origin(q.origin());
        Viewer v = viewer(actor, false);
        WorkspaceId ws = actor.tenant().workspaceId();
        long total = queries.countTodos(ws, v, origin, q.claimable());
        if (total == 0) {
            return Page.empty(page);
        }
        Names names = new Names(actor.tenant());
        List<QueryDao.TodoRow> rows = queries.todos(ws, v, origin, q.claimable(), page.offset(), page.size());
        Map<String, SlaSummary> sla = slaOf(ws, rows.stream().map(QueryDao.TodoRow::instanceNo).distinct().toList());
        List<TodoItem> list = rows.stream()
                .map(r -> new TodoItem(r.taskNo(), r.kind(), r.origin(), r.title(), names.process(r.definitionId()),
                        subject(r.subjectType(), r.subjectRef(), r.subjectDisplay()), r.priorityOrd(), r.createdAtMs(), r.dueAtMs(),
                        claimable(r.status(), r.kind(), r.queueGroup()), r.instanceNo(), r.approvalNo(), r.queueGroup(), sla.get(r.instanceNo())))
                .toList();
        return Page.of(list, total, page);
    }

    @SystemAction("只读：同 todos")
    public TodoCount todoCount(Actor actor) {
        QueryDao.TodoCounts c = queries.todoCounts(actor.tenant().workspaceId(), viewer(actor, false));
        return new TodoCount(c.all(), c.process(), c.ticket(), c.claimable());
    }

    // ── 任务 / 队列 ─────────────────────────────────────────

    @SystemAction("只读：结果按操作人的候选键与可见范围过滤")
    public Page<QueueTask> tasks(Actor actor, TaskQuery q) {
        PageRequest page = PageRequest.of(q.page(), q.size());
        List<String> statuses = checkAll(q.status(), TASK_STATUSES, "status");
        Optional<String> assignee = assignee(actor, q.assignee());
        if (assignee.isPresent() && assignee.get().isEmpty()) {
            return Page.empty(page); // 应用自身问「我的」：没有
        }
        WorkspaceId ws = actor.tenant().workspaceId();
        Viewer v = viewer(actor, actor.isAppItself());
        String who = assignee.orElse(null);
        long total = queries.countTasks(ws, v, q.queueGroup(), statuses, q.claimed(), who);
        if (total == 0) {
            return Page.empty(page);
        }
        Map<String, Optional<Integer>> timeouts = new HashMap<>();
        List<QueueTask> list = queries.tasks(ws, v, q.queueGroup(), statuses, q.claimed(), who, page.offset(), page.size()).stream()
                .map(r -> {
                    Long expires = null;
                    if ("CLAIMED".equals(r.status()) && r.queueGroup() != null && r.claimedAtMs() != null) {
                        Optional<Integer> sec = timeouts.computeIfAbsent(r.queueGroup(), g -> directory.claimTimeoutSec(actor.tenant(), g));
                        expires = sec.map(s -> r.claimedAtMs() + s * 1000L).orElse(null);
                    }
                    return new QueueTask(r.taskNo(), r.kind(), r.status(), r.queueGroup(), r.title(),
                            subject(r.subjectType(), r.subjectRef(), r.subjectDisplay()), r.priorityOrd(), r.createdAtMs(), r.instanceNo(),
                            r.approvalNo(), "CLAIMED".equals(r.status()) ? r.assignee() : null, expires);
                })
                .toList();
        return Page.of(list, total, page);
    }

    /** 成员：自己所在的队列组（含一条任务都没有的）；应用自身：所有有任务的队列组。 */
    @SystemAction("只读：同 tasks")
    public List<QueueCount> queueCounts(Actor actor) {
        TenantContext t = actor.tenant();
        boolean app = actor.isAppItself();
        Map<String, QueueCount> out = new TreeMap<>();
        if (!app) {
            actor.groups().stream().filter(g -> directory.isQueue(t, g)).forEach(g -> out.put(g, new QueueCount(g, 0, 0)));
        }
        queries.queueCounts(t.workspaceId(), viewer(actor, app), app ? null : actor.groups())
                .forEach(r -> out.put(r.queueGroup(), new QueueCount(r.queueGroup(), r.unclaimed(), r.claimedByMe())));
        return List.copyOf(out.values());
    }

    // ── 审批 ────────────────────────────────────────────────

    @SystemAction("只读：结果按提交人 / 参与者 / 审批候选过滤；approval:view:all 不限")
    public Page<ApprovalRow> approvals(Actor actor, ApprovalQuery q) {
        PageRequest page = PageRequest.of(q.page(), q.size());
        List<String> statuses = checkAll(q.status(), APPROVAL_STATUSES, "status");
        WorkspaceId ws = actor.tenant().workspaceId();
        Viewer v = approvalViewer(actor);
        String by = member(q.requestedBy());
        long total = queries.countApprovals(ws, v, statuses, q.processCode(), by, q.fromMs(), q.keyword());
        if (total == 0) {
            return Page.empty(page);
        }
        Names names = new Names(actor.tenant());
        List<ApprovalRow> list = queries.approvals(ws, v, statuses, q.processCode(), by, q.fromMs(), q.keyword(), page.offset(), page.size()).stream()
                .map(r -> {
                    boolean pending = "PENDING".equals(r.status());
                    return new ApprovalRow(r.approvalNo(), r.instanceNo(), r.processCode(), names.process(r.definitionId()),
                            subject(r.subjectType(), r.subjectRef(), r.subjectDisplay()), r.status(), pending ? r.currentStep() : null,
                            pending ? names.step(r.definitionId(), r.gateStatus(), r.currentStep()) : null, r.currentRound(), r.requestedBy(),
                            r.requestedAtMs(), r.finishedAtMs(), pending ? r.stepStartedAtMs() : null, pending ? r.stepDueAtMs() : null);
                })
                .toList();
        return Page.of(list, total, page);
    }

    /** 单张审批的行 + 定义侧信息；看不见按不存在（与列表同一个可见口径）。 */
    @SystemAction("只读：同 approvals")
    public Optional<ApprovalDetail> approvalDetail(Actor actor, String approvalNo) {
        TenantContext t = actor.tenant();
        return queries.approvals(t.workspaceId(), approvalViewer(actor), null, null, null, null, approvalNo, 0, 2).stream()
                .filter(r -> r.approvalNo().equals(approvalNo)).findFirst()
                .map(r -> {
                    Names names = new Names(t);
                    boolean pending = "PENDING".equals(r.status());
                    ApprovalRow row = new ApprovalRow(r.approvalNo(), r.instanceNo(), r.processCode(), names.process(r.definitionId()),
                            subject(r.subjectType(), r.subjectRef(), r.subjectDisplay()), r.status(), pending ? r.currentStep() : null,
                            pending ? names.step(r.definitionId(), r.gateStatus(), r.currentStep()) : null, r.currentRound(), r.requestedBy(),
                            r.requestedAtMs(), r.finishedAtMs(), pending ? r.stepStartedAtMs() : null, pending ? r.stepDueAtMs() : null);
                    GateDef.Approval gate = (GateDef.Approval) definitions.byDefinitionId(t, r.definitionId()).status(r.gateStatus()).gate();
                    return new ApprovalDetail(row, gate.steps(), gate.rules());
                });
    }

    /** 按状态计数，四种状态都给（没有的为 0）；参数同 approvals（status 不参与）。 */
    @SystemAction("只读：同 approvals")
    public Map<String, Long> approvalCount(Actor actor, ApprovalQuery q) {
        Map<String, Long> got = queries.approvalCountsByStatus(actor.tenant().workspaceId(), approvalViewer(actor), q.processCode(),
                member(q.requestedBy()), q.fromMs(), q.keyword());
        Map<String, Long> out = new LinkedHashMap<>();
        for (String s : List.of("PENDING", "APPROVED", "REJECTED", "WITHDRAWN")) {
            out.put(s, got.getOrDefault(s, 0L));
        }
        return out;
    }

    // ── 内部 ────────────────────────────────────────────────

    /** 必须先领取才能办（与 QueryDao 的 CLAIMABLE 同口径）：普通审批的候选可以直接决定，不算。 */
    static boolean claimable(String status, String kind, String queueGroup) {
        return "OPEN".equals(status) && ("WORK".equals(kind) || queueGroup != null);
    }

    /** 每个实例取到期最近的未停指标（逾期未停的也算：它最该被看见）。 */
    private Map<String, SlaSummary> slaOf(WorkspaceId ws, List<String> instanceNos) {
        Map<String, SlaSummary> out = new HashMap<>();
        if (slaClocks == null || instanceNos.isEmpty()) {
            return out;
        }
        for (SlaClockDao.Row c : slaClocks.ofInstances(ws, instanceNos)) {
            if (!c.active() || c.startedAtMs() == null || c.dueAtMs() == null) {
                continue;
            }
            SlaSummary s = new SlaSummary(c.state(), c.startedAtMs(), c.dueAtMs(), c.pausedMs());
            out.merge(c.instanceNo(), s, (a, b) -> a.dueAtMs() <= b.dueAtMs() ? a : b);
        }
        return out;
    }

    private static Viewer viewer(Actor actor, boolean all) {
        return new Viewer(all, actor.member().map(PrincipalRef::key).orElse(null), CandidateKeys.of(actor), actor.scopes());
    }

    private static Viewer approvalViewer(Actor actor) {
        return viewer(actor, actor.isAppItself() || actor.productPermissions().contains(APPROVAL_VIEW_ALL));
    }

    /** {@code "me"} → 操作人（应用自身时为空串，表示「没有」）；成员 ID → {@code member:x}。 */
    private static Optional<String> assignee(Actor actor, String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        if ("me".equals(raw)) {
            return Optional.of(actor.member().map(PrincipalRef::key).orElse(""));
        }
        return Optional.of(member(raw));
    }

    private static String member(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.startsWith("member:") ? raw : PrincipalRef.member(raw).key();
    }

    private static String origin(String raw) {
        if (raw == null || "ALL".equals(raw)) {
            return null;
        }
        if (!ORIGINS.contains(raw)) {
            throw invalid("origin", "opskit.query.unknown_value");
        }
        return raw;
    }

    private static List<String> checkAll(List<String> values, Set<String> allowed, String path) {
        if (values == null) {
            return List.of();
        }
        List<FieldError> errors = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            if (!allowed.contains(values.get(i))) {
                errors.add(new FieldError(path + "[" + i + "]", "opskit.query.unknown_value"));
            }
        }
        if (!errors.isEmpty()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "unknown " + path + " value", Map.of("allowed", List.copyOf(allowed)), errors);
        }
        return values;
    }

    private static OpsKitException invalid(String path, String error) {
        return new OpsKitException(CommonErrors.VALIDATION, "invalid " + path, Map.of(), List.of(new FieldError(path, error)));
    }

    private static SubjectRef subject(String type, String ref, Map<String, Object> display) {
        return type == null ? null : new SubjectRef(type, ref, display);
    }

    /** 一页之内按定义行缓存名称（定义版本不可变，同一页往往是同一个流程）。 */
    private final class Names {
        private final TenantContext tenant;
        private final Map<Long, CompiledProcess> cache = new HashMap<>();

        Names(TenantContext tenant) {
            this.tenant = tenant;
        }

        LocalizedText process(long definitionId) {
            return compiled(definitionId).definition().name();
        }

        LocalizedText step(long definitionId, String gateStatus, String stepCode) {
            if (stepCode == null) {
                return null;
            }
            if (compiled(definitionId).status(gateStatus).gate() instanceof GateDef.Approval a) {
                return a.steps().stream().filter(s -> s.code().equals(stepCode)).findFirst().map(ApprovalStepDef::name).orElse(null);
            }
            return null;
        }

        private CompiledProcess compiled(long id) {
            return cache.computeIfAbsent(id, k -> definitions.byDefinitionId(tenant, k));
        }
    }
}
