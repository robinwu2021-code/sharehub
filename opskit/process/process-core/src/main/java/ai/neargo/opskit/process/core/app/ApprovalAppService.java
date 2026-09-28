package ai.neargo.opskit.process.core.app;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.CandidateKeys;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.commons.condition.ConditionEvaluator;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.ErrorKey;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.event.EventPublisher;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.jdbc.ConditionalUpdate;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.process.api.ProcessErrors;
import ai.neargo.opskit.process.api.definition.ApprovalMode;
import ai.neargo.opskit.process.api.definition.ApprovalStepDef;
import ai.neargo.opskit.process.api.definition.ApproverKind;
import ai.neargo.opskit.process.api.definition.CompiledProcess;
import ai.neargo.opskit.process.api.definition.GateDef;
import ai.neargo.opskit.process.api.definition.StatusDef;
import ai.neargo.opskit.process.api.event.ApprovalCompleted;
import ai.neargo.opskit.process.api.model.ApprovalView;
import ai.neargo.opskit.process.core.port.Directory;
import ai.neargo.opskit.process.store.dao.ApprovalDao;
import ai.neargo.opskit.process.store.dao.InstanceDao;
import ai.neargo.opskit.process.store.dao.ParticipantDao;
import ai.neargo.opskit.process.store.dao.TaskDao;
import ai.neargo.opskit.process.store.dao.TransitionLogDao;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 审批门（21/07 O2-5 · 21/02 §6.4 · §8.3）：进入挂审批门的状态即开一轮审批；结果经系统迁移推进实例。
 *
 * <p>五条内建规则：① 不能审自己提交的（可关）② 驳回必填原因 ③ 只有发起人能在待审时撤回 ④ 并发只一人赢（条件更新）
 * ⑤ 同一轮同一人不能审两步（可关）。决定的校验顺序：先 409 → 再 403 → 最后 422（2026-09-28 与前端对齐）。
 */
public class ApprovalAppService implements StatusHook {

    private final ProcessDefinitionAppService definitions;
    private final ApprovalDao approvals;
    private final TaskDao tasks;
    private final InstanceDao instances;
    private final ParticipantDao participants;
    private final TransitionLogDao logs;
    private final TaskAppService taskService;
    private final Supplier<InstanceAppService> instanceService;
    private final Directory directory;
    private final EventPublisher events;
    private final BizNoGenerator ids;
    private final Clock clock;
    private final TransactionOperations tx;

    /** @param instanceService 延迟取：实例服务的钩子里有本类，直接注入会形成创建期循环 */
    public ApprovalAppService(ProcessDefinitionAppService definitions, ApprovalDao approvals, TaskDao tasks, InstanceDao instances,
            ParticipantDao participants, TransitionLogDao logs, TaskAppService taskService, Supplier<InstanceAppService> instanceService,
            Directory directory, EventPublisher events, BizNoGenerator ids, Clock clock, TransactionOperations tx) {
        this.definitions = definitions;
        this.approvals = approvals;
        this.tasks = tasks;
        this.instances = instances;
        this.participants = participants;
        this.logs = logs;
        this.taskService = taskService;
        this.instanceService = instanceService;
        this.directory = directory;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
        this.tx = tx;
    }

    // ── 门：进入 / 离开 ──────────────────────────────────────
    @Override
    @SystemAction("状态钩子：进入审批门即开一轮")
    public void onEnter(HookContext ctx, StatusDef status) {
        if (!(status.gate() instanceof GateDef.Approval gate)) {
            return;
        }
        WorkspaceId ws = ctx.tenant().workspaceId();
        long now = clock.millis();
        String by = ctx.actor().map(InstanceAppService::who).orElse("system");
        List<String> plan = gate.steps().stream().filter(s -> s.when() == null || ConditionEvaluator.evaluate(s.when(), ctx.fields()))
                .map(ApprovalStepDef::code).toList();
        String first = plan.isEmpty() ? null : plan.get(0);
        Optional<ApprovalDao.Row> existing = approvals.ofGate(ws, ctx.instanceNo(), status.code());
        String no;
        int round;
        if (existing.isPresent()) {
            no = existing.get().approvalNo();
            ConditionalUpdate.requireExactlyOne(approvals.reopen(ws, no, existing.get().version(), first, by, now), "approval " + no);
            round = existing.get().currentRound() + 1;
        } else {
            no = ids.next("AP");
            approvals.insert(ws, new ApprovalDao.Row(ctx.tenant().accountId().value(), ws.value(), no, ctx.instanceNo(), status.code(), 1, first,
                    "PENDING", by, now, null, 0), first);
            round = 1;
        }
        approvals.insertRound(ws, new ApprovalDao.Round(no, round, new ArrayList<>(plan), ctx.fields(), Map.of(), by, now, null, null));
        log(ctx.tenant(), no, ctx.instanceNo(), "submitted", null, "PENDING", by, null, now);
        if (plan.isEmpty()) {
            // 所有步骤都被条件跳过：当场通过（出口迁移经实例服务的队列，在本次迁移走完后执行）
            complete(ctx.tenant(), load(ws, no), round, "APPROVED", null, "system", gate);
            return;
        }
        startStep(ctx.tenant(), no, round, step(gate, first), ctx.fields(), ctx.instanceNo());
    }

    /** 以非门出口的方式离开（如发起人撤销整张单）：还在待审的审批收尾为 WITHDRAWN，不再触发门出口。 */
    @Override
    @SystemAction("状态钩子")
    public void onLeave(HookContext ctx, StatusDef status) {
        if (!(status.gate() instanceof GateDef.Approval)) {
            return;
        }
        WorkspaceId ws = ctx.tenant().workspaceId();
        approvals.ofGate(ws, ctx.instanceNo(), status.code()).filter(a -> "PENDING".equals(a.status())).ifPresent(a -> {
            cancelOpenTasks(ctx.tenant(), a, "left the approval status");
            approvals.finishRound(ws, a.approvalNo(), a.currentRound(), "WITHDRAWN", clock.millis());
            ConditionalUpdate.requireExactlyOne(approvals.finish(ws, a.approvalNo(), a.version(), "WITHDRAWN", clock.millis()), "approval " + a.approvalNo());
            events.publish(new ApprovalCompleted(ctx.tenant(), a.approvalNo(), a.instanceNo(), a.currentRound(), "WITHDRAWN", null,
                    ctx.actor().map(InstanceAppService::who).orElse("system"), clock.instant()));
        });
    }

    // ── 决定 / 撤回 ──────────────────────────────────────────
    @SystemAction("资格由审批规则与候选判定")
    public ApprovalView decide(Actor actor, String approvalNo, String taskNo, boolean approve, String reason) {
        return tx.execute(s -> {
            TenantContext t = actor.tenant();
            ApprovalDao.Row a = load(t.workspaceId(), approvalNo);
            if (!canSee(actor, a)) {
                throw new OpsKitException(CommonErrors.NOT_FOUND, "approval not found");
            }
            TaskDao.Row task = tasks.find(t.workspaceId(), taskNo).filter(x -> approvalNo.equals(x.approvalNo()))
                    .orElseThrow(() -> new OpsKitException(CommonErrors.NOT_FOUND, "task not found"));
            GateDef.Approval gate = gateOf(t, a);
            Optional<ErrorKey> blocked = check(actor, a, task, gate);
            if (blocked.isPresent()) {
                throw new OpsKitException(blocked.get(), "cannot decide: " + blocked.get().key());
            }
            if (!approve && (reason == null || reason.isBlank())) {
                throw new OpsKitException(ProcessErrors.REASON_REQUIRED, "rejecting needs a reason");
            }
            String by = InstanceAppService.who(actor);
            taskService.finishTask(t, task, "DONE", approve ? "APPROVE" : "REJECT", reason, by);
            log(t, approvalNo, a.instanceNo(), approve ? "approve" : "reject", task.stepCode(), null, by, reason, clock.millis());
            if (!approve) {
                cancelOpenTasks(t, a, "rejected in the same round");
                complete(t, load(t.workspaceId(), approvalNo), a.currentRound(), "REJECTED", reason, by, gate);
                return view(actor, approvalNo);
            }
            ApprovalStepDef step = step(gate, task.stepCode());
            List<TaskDao.Row> stepTasks = approvals.tasksOfRound(t.workspaceId(), approvalNo, a.currentRound(), tasks).stream()
                    .filter(x -> x.stepCode().equals(task.stepCode())).toList();
            boolean stepDone = step.mode() == ApprovalMode.ANY || stepTasks.stream().allMatch(x -> "DONE".equals(x.status()));
            if (!stepDone) {
                return view(actor, approvalNo);
            }
            if (step.mode() == ApprovalMode.ANY) {
                stepTasks.stream().filter(x -> isOpen(x.status())).forEach(x -> taskService.finishTask(t, x, "CANCELLED", null, "step approved", by));
            }
            List<String> plan = planOf(t.workspaceId(), approvalNo, a.currentRound());
            int idx = plan.indexOf(task.stepCode());
            if (idx + 1 < plan.size()) {
                String next = plan.get(idx + 1);
                ApprovalDao.Row fresh = load(t.workspaceId(), approvalNo);
                ConditionalUpdate.requireExactlyOne(approvals.moveToStep(t.workspaceId(), approvalNo, fresh.version(), next), "approval " + approvalNo);
                InstanceDao.Row inst = instances.find(t.workspaceId(), a.instanceNo()).orElseThrow();
                startStep(t, approvalNo, a.currentRound(), step(gate, next), inst.fields(), a.instanceNo());
            } else {
                complete(t, load(t.workspaceId(), approvalNo), a.currentRound(), "APPROVED", null, by, gate);
            }
            return view(actor, approvalNo);
        });
    }

    @SystemAction("规则 ③：只有发起人、只在待审")
    public ApprovalView withdraw(Actor actor, String approvalNo) {
        return tx.execute(s -> {
            TenantContext t = actor.tenant();
            ApprovalDao.Row a = load(t.workspaceId(), approvalNo);
            if (!"PENDING".equals(a.status())) {
                throw new OpsKitException(CommonErrors.STATE_CHANGED, "approval is " + a.status());
            }
            if (!InstanceAppService.who(actor).equals(a.requestedBy())) {
                throw new OpsKitException(ProcessErrors.WITHDRAW_FORBIDDEN, "only the requester can withdraw");
            }
            GateDef.Approval gate = gateOf(t, a);
            cancelOpenTasks(t, a, "withdrawn");
            complete(t, a, a.currentRound(), "WITHDRAWN", null, InstanceAppService.who(actor), gate);
            return view(actor, approvalNo);
        });
    }

    @SystemAction("发起人、候选 / 办理人、实例参与者与应用自身可读")
    public ApprovalView get(Actor actor, String approvalNo) {
        ApprovalDao.Row a = load(actor.tenant().workspaceId(), approvalNo);
        if (!canSee(actor, a)) {
            throw new OpsKitException(CommonErrors.NOT_FOUND, "approval not found");
        }
        return view(actor, approvalNo);
    }

    // ── 内部 ─────────────────────────────────────────────────
    /**
     * 与「决定」完全相同的检查（只读）：详情页的 whyNot 与真正的拦截永远一致。顺序：409 → 403。
     */
    private Optional<ErrorKey> check(Actor actor, ApprovalDao.Row a, TaskDao.Row task, GateDef.Approval gate) {
        if (!"PENDING".equals(a.status()) || !isOpen(task.status()) || task.roundNo() == null || task.roundNo() != a.currentRound()) {
            return Optional.of(CommonErrors.STATE_CHANGED);
        }
        String me = actor.member().map(PrincipalRef::key).orElse(null);
        if (me == null) {
            return Optional.of(ProcessErrors.NOT_CANDIDATE);
        }
        if (!gate.rules().selfApproval() && me.equals(a.requestedBy())) {
            return Optional.of(ProcessErrors.SELF_REVIEW);
        }
        if (gate.rules().crossStepExclusive()) {
            boolean decidedOtherStep = approvals.tasksOfRound(actor.tenant().workspaceId(), a.approvalNo(), a.currentRound(), tasks).stream()
                    .anyMatch(x -> me.equals(x.completedBy()) && x.decision() != null && !x.stepCode().equals(task.stepCode()));
            if (decidedOtherStep) {
                return Optional.of(ProcessErrors.CROSS_STEP);
            }
        }
        if (task.assignee() != null) {
            return me.equals(task.assignee()) ? Optional.empty() : Optional.of(ProcessErrors.NOT_CANDIDATE);
        }
        Set<String> mine = CandidateKeys.of(actor);
        return tasks.candidates(actor.tenant().workspaceId(), task.taskNo()).stream().anyMatch(mine::contains)
                ? Optional.empty() : Optional.of(ProcessErrors.NOT_CANDIDATE);
    }

    private void startStep(TenantContext t, String approvalNo, int round, ApprovalStepDef step, Map<String, Object> fields, String instanceNo) {
        Long due = step.due() == null ? null : clock.millis() + step.due().toMillis();
        String title = step.name() == null ? step.code() : step.name().resolve("zh", "en");
        if (step.mode() == ApprovalMode.ANY) {
            List<String> keys = taskService.candidateKeys(t, instanceNo, step.approvers(), fields);
            if (keys.isEmpty()) {
                throw new OpsKitException(ProcessErrors.NO_APPROVER, "no approver for step " + step.code());
            }
            String queue = step.approvers().kind() == ApproverKind.GROUP && step.approvers().values().size() == 1 ? step.approvers().values().get(0) : null;
            taskService.create(t, instanceNo, TaskAppService.APPROVAL, approvalNo, round, step.code(), queue, title, null, due, keys, "PROCESS");
            return;
        }
        // 会签：按人头建任务；组在进入这一步时快照组员
        Set<String> people = new LinkedHashSet<>();
        for (String v : step.approvers().values()) {
            switch (step.approvers().kind()) {
                case MEMBER -> people.add(PrincipalRef.member(v).key());
                case GROUP -> directory.membersOf(t, v).forEach(m -> people.add(PrincipalRef.member(m).key()));
                case FIELD -> taskService.candidateKeys(t, instanceNo, new ai.neargo.opskit.process.api.definition.ApproverRef(ApproverKind.FIELD, List.of(v)), fields)
                        .stream().filter(k -> k.startsWith("member:")).forEach(people::add);
                default -> throw new IllegalStateException("countersign needs enumerable approvers; validator should have rejected " + step.approvers().kind());
            }
        }
        if (people.isEmpty()) {
            // 一个审批人都枚举不出来：宁可让进入这个门的迁移整体失败，也不留下一张谁也批不了的单
            throw new OpsKitException(ProcessErrors.NO_APPROVER, "no approver for countersign step " + step.code());
        }
        for (String p : people) {
            taskService.create(t, instanceNo, TaskAppService.APPROVAL, approvalNo, round, step.code(), null, title, null, due, List.of(p), "PROCESS");
        }
    }

    /** 本轮结束：收尾轮次与审批单、发事件，再经实例服务触发门出口（若已在迁移中则排队到其后）。 */
    private void complete(TenantContext t, ApprovalDao.Row a, int round, String result, String reason, String by, GateDef.Approval gate) {
        long now = clock.millis();
        approvals.finishRound(t.workspaceId(), a.approvalNo(), round, result, now);
        ConditionalUpdate.requireExactlyOne(approvals.finish(t.workspaceId(), a.approvalNo(), a.version(), result, now), "approval " + a.approvalNo());
        log(t, a.approvalNo(), a.instanceNo(), "completed", "PENDING", result, by, reason, now);
        events.publish(new ApprovalCompleted(t, a.approvalNo(), a.instanceNo(), round, result, reason, by, clock.instant()));
        String exit = switch (result) {
            case "APPROVED" -> gate.onApproved();
            case "REJECTED" -> gate.onRejected();
            default -> gate.onWithdrawn();
        };
        if (exit != null) {
            instanceService.get().fireSystem(t, a.instanceNo(), exit, by, reason);
        }
    }

    private void cancelOpenTasks(TenantContext t, ApprovalDao.Row a, String why) {
        approvals.tasksOfRound(t.workspaceId(), a.approvalNo(), a.currentRound(), tasks).stream().filter(x -> isOpen(x.status()))
                .forEach(x -> taskService.finishTask(t, x, "CANCELLED", null, why, "system"));
    }

    private GateDef.Approval gateOf(TenantContext t, ApprovalDao.Row a) {
        InstanceDao.Row inst = instances.find(t.workspaceId(), a.instanceNo()).orElseThrow();
        CompiledProcess p = definitions.byDefinitionId(t, inst.definitionId());
        return (GateDef.Approval) p.status(a.gateStatus()).gate();
    }

    private List<String> planOf(WorkspaceId ws, String approvalNo, int round) {
        return approvals.rounds(ws, approvalNo).stream().filter(r -> r.roundNo() == round).findFirst()
                .map(r -> r.plan().stream().map(String::valueOf).toList()).orElse(List.of());
    }

    /**
     * 审批的可见（与 ProcessQueryService 的列表同一口径，21/02 §7.2）：应用自身 ∨ 查看全部 ∨ 提交人 ∨ 参与者
     * ∨（审批任务的候选 / 处理人 ∧ 实例对我可见：未打范围标签或标签相交）。数据范围外按不存在处理。
     */
    private boolean canSee(Actor actor, ApprovalDao.Row a) {
        if (actor.isAppItself() || actor.productPermissions().contains("approval:view:all") || InstanceAppService.who(actor).equals(a.requestedBy())) {
            return true;
        }
        Set<String> mine = CandidateKeys.of(actor);
        if (!participants.rolesOf(actor.tenant().workspaceId(), a.instanceNo(), mine).isEmpty()) {
            return true;
        }
        Set<String> tags = instances.scopeTags(actor.tenant().workspaceId(), a.instanceNo());
        if (!tags.isEmpty() && tags.stream().noneMatch(actor.scopes()::contains)) {
            return false;
        }
        String me = actor.member().map(PrincipalRef::key).orElse("");
        boolean involved = approvals.rounds(actor.tenant().workspaceId(), a.approvalNo()).stream()
                .flatMap(r -> approvals.tasksOfRound(actor.tenant().workspaceId(), a.approvalNo(), r.roundNo(), tasks).stream())
                .anyMatch(x -> me.equals(x.assignee()) || me.equals(x.completedBy())
                        || tasks.candidates(actor.tenant().workspaceId(), x.taskNo()).stream().anyMatch(mine::contains));
        return involved;
    }

    private ApprovalView view(Actor actor, String approvalNo) {
        WorkspaceId ws = actor.tenant().workspaceId();
        ApprovalDao.Row a = load(ws, approvalNo);
        GateDef.Approval gate = gateOf(actor.tenant(), a);
        List<ApprovalView.RoundView> rounds = new ArrayList<>();
        ApprovalView.Mine mine = new ApprovalView.Mine(null, false, null, false);
        for (ApprovalDao.Round r : approvals.rounds(ws, approvalNo)) {
            List<TaskDao.Row> rt = approvals.tasksOfRound(ws, approvalNo, r.roundNo(), tasks);
            List<String> plan = r.plan().stream().map(String::valueOf).toList();
            List<ApprovalView.StepView> steps = new ArrayList<>();
            for (ApprovalStepDef sd : gate.steps()) {
                List<ApprovalView.Decision> ds = rt.stream().filter(x -> sd.code().equals(x.stepCode()))
                        .map(x -> new ApprovalView.Decision(x.taskNo(), x.status(), x.completedBy(), x.decision(), x.reason(), x.completedAtMs())).toList();
                String state = !plan.contains(sd.code()) ? "SKIPPED"
                        : sd.code().equals(a.currentStep()) && r.roundNo() == a.currentRound() && "PENDING".equals(a.status()) ? "CURRENT"
                        : ds.stream().anyMatch(d -> d.decision() != null) && ds.stream().noneMatch(d -> isOpen(d.status())) ? "DONE" : "TODO";
                steps.add(new ApprovalView.StepView(sd.code(), state, ds));
            }
            rounds.add(new ApprovalView.RoundView(r.roundNo(), r.result(), r.submittedBy(), r.submittedAtMs(), r.finishedAtMs(), steps));
            if (r.roundNo() == a.currentRound() && "PENDING".equals(a.status())) {
                Optional<TaskDao.Row> myTask = rt.stream().filter(x -> isOpen(x.status()) && sd(x, a)).filter(x -> check(actor, a, x, gate).isEmpty()).findFirst();
                Optional<TaskDao.Row> anyTask = rt.stream().filter(x -> isOpen(x.status()) && sd(x, a)).findFirst();
                String whyNot = myTask.isPresent() ? null : anyTask.flatMap(x -> check(actor, a, x, gate)).map(ErrorKey::key).orElse(null);
                mine = new ApprovalView.Mine(myTask.map(TaskDao.Row::taskNo).orElse(null), myTask.isPresent(), whyNot,
                        InstanceAppService.who(actor).equals(a.requestedBy()));
            }
        }
        return new ApprovalView(a.approvalNo(), a.instanceNo(), a.gateStatus(), a.status(), a.currentRound(), a.currentStep(), a.requestedBy(),
                a.requestedAtMs(), a.finishedAtMs(), rounds, mine);
    }

    private static boolean sd(TaskDao.Row x, ApprovalDao.Row a) {
        return x.stepCode() != null && x.stepCode().equals(a.currentStep());
    }

    private void log(TenantContext t, String approvalNo, String instanceNo, String action, String from, String to, String by, String reason, long now) {
        logs.insert(t.workspaceId(), new TransitionLogDao.Entry(t.accountId().value(), "APPROVAL", approvalNo, instanceNo, null, null, action, from, to,
                null, by, null, reason, Map.of(), now));
    }

    private ApprovalDao.Row load(WorkspaceId ws, String approvalNo) {
        return approvals.find(ws, approvalNo).orElseThrow(() -> new OpsKitException(CommonErrors.NOT_FOUND, "approval not found"));
    }

    private static ApprovalStepDef step(GateDef.Approval g, String code) {
        return g.steps().stream().filter(s -> s.code().equals(code)).findFirst().orElseThrow();
    }

    private static boolean isOpen(String status) {
        return "OPEN".equals(status) || "CLAIMED".equals(status) || "ASSIGNED".equals(status);
    }
}
