package ai.neargo.opskit.process.core.app;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.CandidateKeys;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.event.EventPublisher;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.jdbc.ConditionalUpdate;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.commons.timer.TimerId;
import ai.neargo.opskit.commons.timer.TimerScheduler;
import ai.neargo.opskit.process.api.ProcessErrors;
import ai.neargo.opskit.process.api.definition.ApproverKind;
import ai.neargo.opskit.process.api.definition.ApproverRef;
import ai.neargo.opskit.process.api.definition.StatusDef;
import ai.neargo.opskit.process.api.event.TaskEvent;
import ai.neargo.opskit.process.api.model.TaskView;
import ai.neargo.opskit.process.core.port.Directory;
import ai.neargo.opskit.process.store.dao.ParticipantDao;
import ai.neargo.opskit.process.store.dao.TaskDao;
import ai.neargo.opskit.process.store.dao.TransitionLogDao;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 人工任务（21/07 O2-4）：建 · 领取 / 放回 · 指派 / 转派 / 退回池 · 结束 · 领取超时。
 *
 * <p>办理任务跟着状态走：迁移离开某状态时，该状态的办理任务自动完成（{@link #onLeave}）；新状态上的 AssignTo 动作再建新任务
 * （{@link #assign(HookContext, ApproverRef)}）。所以办理人「完成任务」就是去走一条迁移，不需要单独的完成接口。
 * 审批任务由审批门（O2-5）经 {@link #finishTask} 结束。
 */
public class TaskAppService implements AssignmentHandler, StatusHook {

    public static final String WORK = "WORK";
    public static final String APPROVAL = "APPROVAL";
    public static final String CLAIM_TIMEOUT_TASK = "claim-timeout";
    private static final int DEFAULT_CLAIM_TIMEOUT_SEC = 1800;

    private final TaskDao tasks;
    private final ParticipantDao participants;
    private final Directory directory;
    private final TimerScheduler timers;
    private final EventPublisher events;
    private final BizNoGenerator ids;
    private final Clock clock;
    private final TransactionOperations tx;
    private final TransitionLogDao logs;

    public TaskAppService(TaskDao tasks, ParticipantDao participants, Directory directory, TimerScheduler timers, EventPublisher events,
            BizNoGenerator ids, Clock clock, TransactionOperations tx) {
        this(tasks, participants, directory, timers, events, ids, clock, tx, null);
    }

    /** @param logs 任务的每次变化写进实例历史（entity_kind = TASK）；为空时不写（只在不关心历史的单元测试里） */
    public TaskAppService(TaskDao tasks, ParticipantDao participants, Directory directory, TimerScheduler timers, EventPublisher events,
            BizNoGenerator ids, Clock clock, TransactionOperations tx, TransitionLogDao logs) {
        this.logs = logs;
        this.tasks = tasks;
        this.participants = participants;
        this.directory = directory;
        this.timers = timers;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
        this.tx = tx;
    }

    // ── 建任务 ───────────────────────────────────────────────
    /** 后置动作 AssignTo：唯一的具体成员 → 直接指派；否则 → 候选池（可领取）。 */
    @Override
    @SystemAction("迁移的后置动作 AssignTo 回调，授权已由迁移的 who 判定")
    public void assign(HookContext ctx, ApproverRef candidates) {
        List<String> keys = candidateKeys(ctx.tenant(), ctx.instanceNo(), candidates, ctx.fields());
        String queue = candidates.kind() == ApproverKind.GROUP && candidates.values().size() == 1 ? candidates.values().get(0) : null;
        create(ctx.tenant(), ctx.instanceNo(), WORK, null, null, null, queue, null, null, null, keys, "PROCESS");
    }

    /**
     * 建任务（办理或审批）。唯一候选是具体成员时直接指派，并把他设为实例的 ASSIGNEE 参与者。须在调用方事务里调用。
     */
    @SystemAction("引擎内部调用：AssignTo 动作与审批门")
    public String create(TenantContext t, String instanceNo, String kind, String approvalNo, Integer roundNo, String stepCode, String queueGroup,
            String title, Integer priorityOrd, Long dueAtMs, List<String> candidateKeys, String origin) {
        if (candidateKeys.isEmpty()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "task needs at least one candidate", Map.of(),
                    List.of(new FieldError("candidates", "opskit.process.task.no_candidate")));
        }
        long now = clock.millis();
        String no = ids.next("TS");
        String direct = candidateKeys.size() == 1 && candidateKeys.get(0).startsWith("member:") ? candidateKeys.get(0) : null;
        tasks.insert(t.workspaceId(), new TaskDao.Row(t.accountId().value(), t.workspaceId().value(), no, kind, instanceNo, approvalNo, roundNo,
                stepCode, queueGroup, title, priorityOrd, direct == null ? "OPEN" : "ASSIGNED", direct, null, 0, null, null, null, null, dueAtMs, 0,
                now, now, 0));
        tasks.addCandidates(t.workspaceId(), no, instanceNo, kind, origin, priorityOrd, dueAtMs, candidateKeys, now);
        if (WORK.equals(kind)) {
            participants.removeRole(t.workspaceId(), instanceNo, InstanceAppService.ASSIGNEE);
            if (direct != null) {
                participants.add(t.workspaceId(), instanceNo, InstanceAppService.ASSIGNEE, direct, now);
            }
        }
        events.publish(new TaskEvent(t, "created", no, kind, instanceNo, direct, 0, Instant.ofEpochMilli(now)));
        trace(t, no, instanceNo, "created", null, direct == null ? "OPEN" : direct, "system", null);
        return no;
    }

    // ── 领取 / 放回 ──────────────────────────────────────────
    @SystemAction("资格由候选判定：只有候选人能领")
    public TaskView claim(Actor actor, String taskNo) {
        String me = requireMember(actor);
        return tx.execute(s -> {
            TenantContext t = actor.tenant();
            TaskDao.Row row = load(t.workspaceId(), taskNo);
            requireCandidate(actor, t.workspaceId(), row);
            long now = clock.millis();
            ConditionalUpdate.requireExactlyOne(tasks.claim(t.workspaceId(), taskNo, me, now), "task " + taskNo);
            TaskDao.Row claimed = load(t.workspaceId(), taskNo);
            if (WORK.equals(claimed.kind())) {
                participants.removeRole(t.workspaceId(), claimed.instanceNo(), InstanceAppService.ASSIGNEE);
                participants.add(t.workspaceId(), claimed.instanceNo(), InstanceAppService.ASSIGNEE, me, now);
            }
            int timeout = claimed.queueGroup() == null ? DEFAULT_CLAIM_TIMEOUT_SEC
                    : directory.claimTimeoutSec(t, claimed.queueGroup()).orElse(DEFAULT_CLAIM_TIMEOUT_SEC);
            timers.schedule(CLAIM_TIMEOUT_TASK, new TimerId(t.workspaceId(), taskNo, claimed.claimEpoch()), Instant.ofEpochMilli(now + timeout * 1000L));
            events.publish(new TaskEvent(t, "assigned", taskNo, claimed.kind(), claimed.instanceNo(), me, claimed.version(), Instant.ofEpochMilli(now)));
            trace(t, taskNo, claimed.instanceNo(), "claimed", null, me, me, null);
            return view(t, claimed, timeout);
        });
    }

    @SystemAction("只有领取人本人能放回")
    public TaskView release(Actor actor, String taskNo) {
        String me = requireMember(actor);
        return tx.execute(s -> {
            TenantContext t = actor.tenant();
            TaskDao.Row row = load(t.workspaceId(), taskNo);
            if (!"CLAIMED".equals(row.status()) || !me.equals(row.assignee())) {
                throw new OpsKitException(ProcessErrors.NOT_ALLOWED, "only the claimer can release");
            }
            ConditionalUpdate.requireExactlyOne(tasks.release(t.workspaceId(), taskNo, me, null, clock.millis()), "task " + taskNo);
            TaskDao.Row released = load(t.workspaceId(), taskNo);
            if (WORK.equals(released.kind())) {
                participants.remove(t.workspaceId(), released.instanceNo(), InstanceAppService.ASSIGNEE, me);
            }
            events.publish(new TaskEvent(t, "assigned", taskNo, released.kind(), released.instanceNo(), null, released.version(), clock.instant()));
            trace(t, taskNo, released.instanceNo(), "released", me, null, me, null);
            return view(t, released, null);
        });
    }

    /**
     * 领取超时（db-scheduler 一次性任务触发）。领取代次不符 = 期间已放回 / 重领 / 办完，什么也不做（epoch 防旧，21/02 §九）。
     *
     * @return true = 确实放回了
     */
    @SystemAction("定时触发")
    public boolean onClaimTimeout(TenantContext t, String taskNo, int claimEpoch) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            Optional<TaskDao.Row> row = tasks.find(t.workspaceId(), taskNo);
            if (row.isEmpty() || !"CLAIMED".equals(row.get().status()) || row.get().claimEpoch() != claimEpoch) {
                return false;
            }
            String who = row.get().assignee();
            if (tasks.release(t.workspaceId(), taskNo, who, claimEpoch, clock.millis()) != 1) {
                return false;
            }
            if (WORK.equals(row.get().kind())) {
                participants.remove(t.workspaceId(), row.get().instanceNo(), InstanceAppService.ASSIGNEE, who);
            }
            events.publish(new TaskEvent(t, "assigned", taskNo, row.get().kind(), row.get().instanceNo(), null, row.get().version() + 1, clock.instant()));
            trace(t, taskNo, row.get().instanceNo(), "claim_timeout", who, null, "system", null);
            return true;
        }));
    }

    // ── 指派 / 转派 / 退回池 ─────────────────────────────────
    /** 指派与转派。转派（已有办理人）必须写原因。谁能派：应用自身或实例的 OWNER（工单模块另按产品权限先拦一道）。 */
    @SystemAction("应用自身或实例 OWNER")
    public TaskView assignTo(Actor actor, String taskNo, String memberRef, String reason) {
        PrincipalRef to = PrincipalRef.parse(memberRef);
        if (to.kind() != PrincipalRef.Kind.MEMBER) {
            throw new OpsKitException(CommonErrors.VALIDATION, "tasks are assigned to members");
        }
        return tx.execute(s -> {
            TenantContext t = actor.tenant();
            TaskDao.Row row = load(t.workspaceId(), taskNo);
            requireDispatcher(actor, row);
            if (row.assignee() != null && (reason == null || reason.isBlank())) {
                throw new OpsKitException(ProcessErrors.REQUIRED_MISSING, "reassigning needs a reason", Map.of(), List.of(new FieldError("reason", "opskit.validation.required")));
            }
            long now = clock.millis();
            ConditionalUpdate.requireExactlyOne(tasks.assign(t.workspaceId(), taskNo, row.version(), to.key(), now), "task " + taskNo);
            if (WORK.equals(row.kind())) {
                participants.removeRole(t.workspaceId(), row.instanceNo(), InstanceAppService.ASSIGNEE);
                participants.add(t.workspaceId(), row.instanceNo(), InstanceAppService.ASSIGNEE, to.key(), now);
            }
            TaskDao.Row assigned = load(t.workspaceId(), taskNo);
            events.publish(new TaskEvent(t, "assigned", taskNo, assigned.kind(), assigned.instanceNo(), to.key(), assigned.version(), clock.instant()));
            trace(t, taskNo, assigned.instanceNo(), row.assignee() == null ? "assigned" : "reassigned", row.assignee(), to.key(), InstanceAppService.who(actor), reason);
            return view(t, assigned, null);
        });
    }

    @SystemAction("应用自身或实例 OWNER")
    public TaskView pool(Actor actor, String taskNo, String reason) {
        return tx.execute(s -> {
            TenantContext t = actor.tenant();
            TaskDao.Row row = load(t.workspaceId(), taskNo);
            requireDispatcher(actor, row);
            ConditionalUpdate.requireExactlyOne(tasks.pool(t.workspaceId(), taskNo, row.version(), clock.millis()), "task " + taskNo);
            if (WORK.equals(row.kind())) {
                participants.removeRole(t.workspaceId(), row.instanceNo(), InstanceAppService.ASSIGNEE);
            }
            TaskDao.Row pooled = load(t.workspaceId(), taskNo);
            events.publish(new TaskEvent(t, "assigned", taskNo, pooled.kind(), pooled.instanceNo(), null, pooled.version(), clock.instant()));
            trace(t, taskNo, pooled.instanceNo(), "pooled", row.assignee(), null, InstanceAppService.who(actor), reason);
            return view(t, pooled, null);
        });
    }

    /** 结束任务（审批决定、状态离开时的收尾）。须在调用方事务里调用；0 行即 409。 */
    @SystemAction("引擎内部调用")
    public void finishTask(TenantContext t, TaskDao.Row row, String status, String decision, String reason, String by) {
        ConditionalUpdate.requireExactlyOne(tasks.finish(t.workspaceId(), row.taskNo(), row.version(), status, decision, reason, by, clock.millis()),
                "task " + row.taskNo());
        events.publish(new TaskEvent(t, "DONE".equals(status) ? "completed" : "cancelled", row.taskNo(), row.kind(), row.instanceNo(),
                row.assignee(), row.version() + 1, clock.instant()));
        trace(t, row.taskNo(), row.instanceNo(), "DONE".equals(status) ? "completed" : "cancelled", row.status(), status, by, reason);
    }

    /** 任务变化写进实例历史（21/06 §七 时间线）：from / to 为办理人（{@code member:x}）或任务状态。 */
    private void trace(TenantContext t, String taskNo, String instanceNo, String action, String from, String to, String by, String reason) {
        if (logs != null) {
            logs.insert(t.workspaceId(), new TransitionLogDao.Entry(t.accountId().value(), "TASK", taskNo, instanceNo, null, null, action, from, to,
                    null, by == null ? "system" : by, null, reason, Map.of(), clock.millis()));
        }
    }

    /** 实例上未结束的办理任务（工单详情里的「当前任务」）。可见性由调用方先判。 */
    @SystemAction("上层模块内部调用：调用方已判实例可见")
    public List<TaskView> openWorkTasks(TenantContext t, String instanceNo) {
        return tasks.unfinishedOfInstance(t.workspaceId(), instanceNo, WORK).stream().map(r -> view(t, r, null)).toList();
    }

    @SystemAction("读")
    public TaskView get(Actor actor, String taskNo) {
        TenantContext t = actor.tenant();
        TaskDao.Row row = load(t.workspaceId(), taskNo);
        Set<String> mine = CandidateKeys.of(actor);
        if (!actor.isAppItself() && !mine.contains(String.valueOf(row.assignee())) && tasks.candidates(t.workspaceId(), taskNo).stream().noneMatch(mine::contains)) {
            throw new OpsKitException(CommonErrors.NOT_FOUND, "task not found");
        }
        return view(t, row, null);
    }

    // ── 状态钩子：办理任务跟着状态走 ─────────────────────────
    @Override
    @SystemAction("状态钩子，随迁移同事务执行")
    public void onLeave(HookContext ctx, StatusDef status) {
        String by = ctx.actor().map(InstanceAppService::who).orElse("system");
        for (TaskDao.Row r : tasks.unfinishedOfInstance(ctx.tenant().workspaceId(), ctx.instanceNo(), WORK)) {
            finishTask(ctx.tenant(), r, "DONE", null, null, by);
        }
    }

    @Override
    @SystemAction("状态钩子，随迁移同事务执行")
    public void onClosed(HookContext ctx) {
        for (TaskDao.Row r : tasks.unfinishedOfInstance(ctx.tenant().workspaceId(), ctx.instanceNo(), APPROVAL)) {
            finishTask(ctx.tenant(), r, "CANCELLED", null, "instance closed", "system");
        }
    }

    /** 兜底扫描：领取超过 maxAgeMs 仍未办完的（调度丢失时补上；每个仍按自己的领取超时判断）。 */
    @SystemAction("定时兜底")
    public List<TaskDao.Row> staleClaims(long maxAgeMs, int limit) {
        return tasks.claimedBefore(AccountId.UNKNOWN, clock.millis() - maxAgeMs, limit);
    }

    // ── 内部 ─────────────────────────────────────────────────
    /** 候选定义 → 候选键。FIELD 取实例字段里的主体；PARTICIPANT 取实例上该角色的当前主体（建任务时快照）。 */
    List<String> candidateKeys(TenantContext t, String instanceNo, ApproverRef ref, Map<String, Object> fields) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : ref.values()) {
            switch (ref.kind()) {
                case MEMBER -> out.add(PrincipalRef.member(v).key());
                case GROUP -> out.add(PrincipalRef.group(v).key());
                case ROLE -> out.add(PrincipalRef.role(v).key());
                case PERM -> out.add(PrincipalRef.perm(v).key());
                case FIELD -> principals(fields.get(v)).forEach(out::add);
                case PARTICIPANT -> out.addAll(participants.byRole(t.workspaceId(), instanceNo).getOrDefault(v, Set.of()));
            }
        }
        return new ArrayList<>(out);
    }

    private static List<String> principals(Object v) {
        Collection<?> c = v instanceof Collection<?> col ? col : (v == null ? List.of() : List.of(v));
        return c.stream().map(x -> {
            String s = String.valueOf(x);
            return s.contains(":") ? PrincipalRef.parse(s).key() : PrincipalRef.member(s).key();
        }).toList();
    }

    private void requireCandidate(Actor actor, WorkspaceId ws, TaskDao.Row row) {
        Set<String> mine = CandidateKeys.of(actor);
        if (tasks.candidates(ws, row.taskNo()).stream().noneMatch(mine::contains)) {
            throw new OpsKitException(ProcessErrors.NOT_ALLOWED, "not a candidate of " + row.taskNo());
        }
    }

    private void requireDispatcher(Actor actor, TaskDao.Row row) {
        if (actor.isAppItself()) {
            return;
        }
        Set<String> roles = participants.rolesOf(actor.tenant().workspaceId(), row.instanceNo(), CandidateKeys.of(actor));
        if (!roles.contains("OWNER")) {
            throw new OpsKitException(ProcessErrors.NOT_ALLOWED, "only the owner can dispatch");
        }
    }

    private static String requireMember(Actor actor) {
        return actor.member().map(PrincipalRef::key)
                .orElseThrow(() -> new OpsKitException(ProcessErrors.NOT_ALLOWED, "claiming needs a member (use onBehalfOf)"));
    }

    private TaskDao.Row load(WorkspaceId ws, String taskNo) {
        return tasks.find(ws, taskNo).orElseThrow(() -> new OpsKitException(CommonErrors.NOT_FOUND, "task not found"));
    }

    private TaskView view(TenantContext t, TaskDao.Row r, Integer timeoutSec) {
        Long expires = r.claimedAtMs() == null ? null
                : r.claimedAtMs() + (timeoutSec != null ? timeoutSec
                        : (r.queueGroup() == null ? DEFAULT_CLAIM_TIMEOUT_SEC : directory.claimTimeoutSec(t, r.queueGroup()).orElse(DEFAULT_CLAIM_TIMEOUT_SEC))) * 1000L;
        return new TaskView(r.taskNo(), r.kind(), r.instanceNo(), r.approvalNo(), r.stepCode(), r.queueGroup(), r.title(), r.priorityOrd(), r.status(),
                r.assignee(), tasks.candidates(t.workspaceId(), r.taskNo()), r.claimedAtMs(), expires, r.dueAtMs(), r.createdAtMs());
    }
}
