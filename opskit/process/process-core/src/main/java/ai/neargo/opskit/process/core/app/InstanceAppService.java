package ai.neargo.opskit.process.core.app;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.CandidateKeys;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.event.EventPublisher;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.jdbc.ConditionalUpdate;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.process.api.ProcessErrors;
import ai.neargo.opskit.process.api.definition.ActionDef;
import ai.neargo.opskit.process.api.definition.CompiledProcess;
import ai.neargo.opskit.process.api.definition.StatusDef;
import ai.neargo.opskit.process.api.definition.TransitionDef;
import ai.neargo.opskit.process.api.event.InstanceTransitioned;
import ai.neargo.opskit.process.api.model.ActionView;
import ai.neargo.opskit.process.api.model.InstanceView;
import ai.neargo.opskit.process.api.model.StartRequest;
import ai.neargo.opskit.process.api.model.SubjectRef;
import ai.neargo.opskit.process.api.model.TransitionRequest;
import ai.neargo.opskit.process.core.domain.instance.Transitioner;
import ai.neargo.opskit.process.store.dao.InstanceDao;
import ai.neargo.opskit.process.store.dao.InstanceScopeDao;
import ai.neargo.opskit.process.store.dao.ParticipantDao;
import ai.neargo.opskit.process.store.dao.TransitionLogDao;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 流程实例（21/07 O2-3）：启动 · 迁移 · 字段 / 参与者 / 标签变更。
 *
 * <p>授权不靠产品权限，靠流程定义的 who 与实例的参与者：同一个人对这张单能不能走这一步，是流程说了算。
 * 一个公开方法 = 一个事务：变化 · 日志 · 后置动作 · 门与 SLA 钩子 · 事件同成同败。
 */
public class InstanceAppService {

    public static final String REQUESTER = "REQUESTER";
    public static final String ASSIGNEE = "ASSIGNEE";

    private final ProcessDefinitionAppService definitions;
    private final InstanceDao instances;
    private final ParticipantDao participants;
    private final InstanceScopeDao scopes;
    private final TransitionLogDao logs;
    private final List<StatusHook> hooks;
    private final Optional<AssignmentHandler> assignments;
    private final EventPublisher events;
    private final BizNoGenerator ids;
    private final Clock clock;
    private final TransactionOperationsFacade tx;

    public InstanceAppService(ProcessDefinitionAppService definitions, InstanceDao instances, ParticipantDao participants, InstanceScopeDao scopes,
            TransitionLogDao logs, List<StatusHook> hooks, Optional<AssignmentHandler> assignments, EventPublisher events, BizNoGenerator ids,
            Clock clock, org.springframework.transaction.support.TransactionOperations tx) {
        this.definitions = definitions;
        this.instances = instances;
        this.participants = participants;
        this.scopes = scopes;
        this.logs = logs;
        this.hooks = List.copyOf(hooks);
        this.assignments = assignments;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
        this.tx = new TransactionOperationsFacade(tx);
    }

    @SystemAction("授权由流程定义与参与者判定；发起即成为 REQUESTER")
    public InstanceView start(Actor actor, StartRequest req) {
        TenantContext t = actor.tenant();
        ProcessDefinitionAppService.Resolved resolved = definitions.resolve(t, req.processCode());
        if (!resolved.enabled()) {
            throw new OpsKitException(ProcessErrors.DEFINITION_DISABLED, "process disabled: " + req.processCode());
        }
        CompiledProcess p = resolved.process();
        return tx.execute(() -> {
            long now = clock.millis();
            String no = ids.next("PI");
            StatusDef initial = p.initial();
            Map<String, Object> fields = new LinkedHashMap<>(req.fields());
            SubjectRef subj = req.subject();
            instances.insert(t.workspaceId(), new InstanceDao.Row(t.accountId().value(), t.workspaceId().value(), no, p.definitionId(),
                    p.definition().code(), p.version(), subj == null ? null : subj.type(), subj == null ? null : subj.ref(),
                    subj == null ? null : subj.display(), initial.code(), initial.category().name(), !initial.category().isTerminal(), fields,
                    gateKind(initial), 1, 1, who(actor), now, now, null, 0));
            Map<String, List<String>> ps = new HashMap<>(req.participants());
            if (!ps.containsKey(REQUESTER) && actor.member().isPresent()) {
                ps.put(REQUESTER, List.of(actor.member().get().key()));
            }
            ps.forEach((role, list) -> list.forEach(pr -> participants.add(t.workspaceId(), no, role, PrincipalRef.parse(pr).key(), now)));
            scopes.replace(t.workspaceId(), no, req.scopes());
            log(t, no, subj, "created", null, initial.code(), 1, actor, null, now);
            HookContext ctx = new HookContext(t, no, p, fields, Optional.of(actor), 1, 1);
            events.publish(new InstanceTransitioned(t, no, p.definition().code(), subj, "created", null, initial.code(), initial.category(),
                    !initial.category().isTerminal(), 1, who(actor), clock.instant()));
            runTopLevel(() -> {
                hooks.forEach(h -> h.onStarted(ctx, initial, req.slaPolicies()));
                hooks.forEach(h -> h.onEnter(ctx, initial));
            });
            return view(actor, no);
        });
    }

    @SystemAction("授权由流程定义的 who 判定")
    public InstanceView transition(Actor actor, String instanceNo, String code, TransitionRequest req) {
        return tx.execute(() -> {
            runTopLevel(() -> fire(actor.tenant(), instanceNo, code, false, Optional.of(actor), req.fields(), req.comment(), null));
            return view(actor, instanceNo);
        });
    }

    /**
     * 门触发的系统迁移（审批结果、外部确认回复、定时到点）。
     *
     * <p>若此刻正处在另一次迁移的钩子里（如进入审批门时所有步骤都被条件跳过、审批当场通过），不立刻执行，
     * 而是排到当前迁移**完整走完之后**、在同一事务里依次执行 —— 否则外层迁移剩下的钩子（SLA 起算等）
     * 会在实例已经离开该状态之后才跑，对着过时的状态动手。
     */
    @SystemAction("门触发，内部调用")
    public void fireSystem(TenantContext t, String instanceNo, String code, String by, String reason) {
        Deque<Runnable> pending = DEFERRED.get();
        Runnable job = () -> fire(t, instanceNo, code, true, Optional.empty(), Map.of(), reason, by);
        if (pending != null) {
            pending.addLast(job);
            return;
        }
        tx.execute(() -> {
            runTopLevel(job);
            return null;
        });
    }

    /** 当前线程正在执行的迁移链；非空 = 处于某次迁移（含其钩子）之中。 */
    private static final ThreadLocal<Deque<Runnable>> DEFERRED = new ThreadLocal<>();

    /** 执行一次迁移，并把它的钩子排下的系统迁移依次跑完（同一事务、先进先出）。 */
    private void runTopLevel(Runnable first) {
        if (DEFERRED.get() != null) {
            first.run();
            return;
        }
        Deque<Runnable> pending = new ArrayDeque<>();
        DEFERRED.set(pending);
        try {
            first.run();
            Runnable next;
            int guard = 0;
            while ((next = pending.pollFirst()) != null) {
                if (++guard > 50) {
                    throw new IllegalStateException("system transitions keep chaining (> 50): the process definition loops through gates");
                }
                next.run();
            }
        } finally {
            DEFERRED.remove();
        }
    }

    @SystemAction("参与者与应用自身可读；完整可见性规则见 O2-9")
    public InstanceView get(Actor actor, String instanceNo) {
        InstanceDao.Row row = load(actor.tenant().workspaceId(), instanceNo);
        if (!actor.isAppItself() && rolesOf(actor, row).isEmpty()) {
            throw new OpsKitException(CommonErrors.NOT_FOUND, "instance not found");
        }
        return view(actor, instanceNo);
    }

    @SystemAction("负责人 / 办理人或应用自身可改字段")
    public InstanceView updateFields(Actor actor, String instanceNo, Map<String, Object> patch) {
        return tx.execute(() -> {
            InstanceDao.Row row = load(actor.tenant().workspaceId(), instanceNo);
            Set<String> roles = rolesOf(actor, row);
            if (!actor.isAppItself() && !roles.contains("OWNER") && !roles.contains(ASSIGNEE)) {
                throw new OpsKitException(ProcessErrors.NOT_ALLOWED, "only owner / assignee can edit fields");
            }
            Map<String, Object> merged = new LinkedHashMap<>(row.fields());
            merged.putAll(patch);
            ConditionalUpdate.requireExactlyOne(instances.updateFields(actor.tenant().workspaceId(), instanceNo, row.version(), merged, clock.millis()),
                    "instance " + instanceNo);
            log(actor.tenant(), instanceNo, subject(row), "fields_updated", null, null, null, actor, null, clock.millis());
            CompiledProcess p = definitions.byDefinitionId(actor.tenant(), row.definitionId());
            HookContext ctx = new HookContext(actor.tenant(), instanceNo, p, merged, Optional.of(actor), row.gateEpoch(), row.eventSeq());
            hooks.forEach(h -> h.onFieldsChanged(ctx, p.status(row.status())));
            return view(actor, instanceNo);
        });
    }

    /** 整体替换某个参与者角色的主体（如工单的关注人 WATCHER）。只允许应用自身（上层模块已判权限）。 */
    @SystemAction("仅应用自身")
    public void replaceParticipants(Actor actor, String instanceNo, String role, Collection<String> principals) {
        if (!actor.isAppItself()) {
            throw new OpsKitException(ProcessErrors.NOT_ALLOWED, "participants are set by the integrating system");
        }
        tx.execute(() -> {
            load(actor.tenant().workspaceId(), instanceNo);
            long now = clock.millis();
            participants.removeRole(actor.tenant().workspaceId(), instanceNo, role);
            principals.forEach(p -> participants.add(actor.tenant().workspaceId(), instanceNo, role, PrincipalRef.parse(p).key(), now));
            return null;
        });
    }

    /** 范围标签由业务决定（它才知道这件事属于哪个站点 / 区域），只允许应用自身改。 */
    @SystemAction("仅应用自身")
    public void replaceScopes(Actor actor, String instanceNo, Collection<String> tags) {
        if (!actor.isAppItself()) {
            throw new OpsKitException(ProcessErrors.NOT_ALLOWED, "scopes are set by the integrating system");
        }
        tx.execute(() -> {
            load(actor.tenant().workspaceId(), instanceNo);
            scopes.replace(actor.tenant().workspaceId(), instanceNo, tags);
            return null;
        });
    }

    @SystemAction("参与者与应用自身可读")
    public List<TransitionLogDao.Entry> history(Actor actor, String instanceNo) {
        get(actor, instanceNo);
        return logs.ofInstance(actor.tenant().workspaceId(), instanceNo);
    }

    // ── 迁移主流程（21/02 §8.1）──────────────────────────────
    private void fire(TenantContext t, String instanceNo, String code, boolean system, Optional<Actor> actor, Map<String, Object> input,
            String comment, String systemBy) {
        InstanceDao.Row row = load(t.workspaceId(), instanceNo);
        if (!row.open()) {
            throw new OpsKitException(ProcessErrors.INSTANCE_CLOSED, "instance is closed");
        }
        CompiledProcess p = definitions.byDefinitionId(t, row.definitionId());
        Map<String, Object> merged = new LinkedHashMap<>(row.fields());
        merged.putAll(input);
        Set<String> roles = actor.map(a -> rolesOf(a, row)).orElse(Set.of());
        Actor decider = actor.orElse(null);
        TransitionDef tr = Transitioner.decide(p, row.status(), code, system, decider == null ? systemActor(t) : decider, roles, merged, comment);
        for (ActionDef a : tr.actions()) {
            if (a instanceof ActionDef.SetField sf) {
                merged.put(sf.field(), sf.value());
            }
        }
        StatusDef from = p.status(row.status());
        StatusDef to = p.status(tr.to());
        long now = clock.millis();
        boolean open = !to.category().isTerminal();
        ConditionalUpdate.requireExactlyOne(instances.transition(t.workspaceId(), instanceNo, row.status(), row.version(), to.code(),
                to.category().name(), open, merged, gateKind(to), now, open ? null : now), "instance " + instanceNo);
        int seq = row.eventSeq() + 1;
        int epoch = row.gateEpoch() + 1;
        String by = actor.map(InstanceAppService::who).orElse(systemBy == null ? "system" : systemBy);
        log(t, instanceNo, subject(row), code, from.code(), to.code(), seq, actor.orElse(null), comment, now, by);
        HookContext ctx = new HookContext(t, instanceNo, p, merged, actor, epoch, seq);
        hooks.forEach(h -> h.onLeave(ctx, from));
        for (ActionDef a : tr.actions()) {
            switch (a) {
                case ActionDef.SetField sf -> {
                    // 已在落库前并入字段
                }
                case ActionDef.AddParticipant ap -> principalsIn(merged.get(ap.fromField()))
                        .forEach(pr -> participants.add(t.workspaceId(), instanceNo, ap.role(), pr, now));
                case ActionDef.ClearAssignee c -> participants.removeRole(t.workspaceId(), instanceNo, ASSIGNEE);
                case ActionDef.AssignTo as -> assignments
                        .orElseThrow(() -> new IllegalStateException("AssignTo needs the task module (O2-4) to be wired"))
                        .assign(ctx, as.candidates());
            }
        }
        events.publish(new InstanceTransitioned(t, instanceNo, row.processCode(), subject(row), code, from.code(), to.code(), to.category(),
                open, seq, by, clock.instant()));
        hooks.forEach(h -> h.onEnter(ctx, to));
        hooks.forEach(h -> h.onTransitioned(ctx, from, to, code));
        if (!open) {
            hooks.forEach(h -> h.onClosed(ctx));
        }
    }

    private InstanceView view(Actor actor, String instanceNo) {
        TenantContext t = actor.tenant();
        InstanceDao.Row row = load(t.workspaceId(), instanceNo);
        CompiledProcess p = definitions.byDefinitionId(t, row.definitionId());
        Set<String> roles = rolesOf(actor, row);
        List<ActionView> actions = row.open()
                ? p.manualTransitionsFrom(row.status()).stream().filter(tr -> Transitioner.canSee(tr, actor, roles, row.fields()))
                        .map(tr -> new ActionView(tr.code(), tr.name() == null ? Map.of() : tr.name().values(), p.status(tr.to()).category(),
                                tr.requires().fields(), tr.requires().comment()))
                        .toList()
                : List.of();
        return new InstanceView(row.instanceNo(), row.processCode(), row.processVersion(), subject(row), row.status(),
                p.status(row.status()).category(), row.open(), row.fields(), participants.byRole(t.workspaceId(), instanceNo),
                scopes.of(t.workspaceId(), instanceNo), row.eventSeq(), actions, row.createdAtMs(), row.updatedAtMs());
    }

    private InstanceDao.Row load(WorkspaceId ws, String instanceNo) {
        return instances.find(ws, instanceNo).orElseThrow(() -> new OpsKitException(CommonErrors.NOT_FOUND, "instance not found"));
    }

    private Set<String> rolesOf(Actor actor, InstanceDao.Row row) {
        return participants.rolesOf(actor.tenant().workspaceId(), row.instanceNo(), CandidateKeys.of(actor));
    }

    private void log(TenantContext t, String no, SubjectRef s, String action, String from, String to, Integer seq, Actor actor, String reason, long now) {
        log(t, no, s, action, from, to, seq, actor, reason, now, actor == null ? "system" : who(actor));
    }

    private void log(TenantContext t, String no, SubjectRef s, String action, String from, String to, Integer seq, Actor actor, String reason,
            long now, String by) {
        String onBehalf = actor != null && actor.member().isPresent() ? actor.app().map(a -> "app:" + a).orElse(null) : null;
        logs.insert(t.workspaceId(), new TransitionLogDao.Entry(t.accountId().value(), "INSTANCE", no, no, s == null ? null : s.type(),
                s == null ? null : s.ref(), action, from, to, seq, by, onBehalf, reason, Map.of(), now));
    }

    private static List<String> principalsIn(Object v) {
        if (v instanceof Collection<?> c) {
            return c.stream().map(x -> PrincipalRef.parse(String.valueOf(x)).key()).toList();
        }
        return v == null ? List.of() : List.of(PrincipalRef.parse(String.valueOf(v)).key());
    }

    private static SubjectRef subject(InstanceDao.Row r) {
        return r.subjectType() == null ? null : new SubjectRef(r.subjectType(), r.subjectRef(), r.subjectDisplay());
    }

    private static String gateKind(StatusDef s) {
        return s.gate() == null ? null : s.gate().getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT);
    }

    static String who(Actor a) {
        return a.member().map(Object::toString).orElseGet(() -> "app:" + a.app().orElse("?"));
    }

    /** 系统迁移没有操作人；Transitioner 在 systemCall=true 时不看 who，这里给一个占位。 */
    private static Actor systemActor(TenantContext t) {
        return new Actor(t, Optional.empty(), Optional.of("system"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    /** 让方法体里的 lambda 能直接返回值、抛运行时异常。 */
    private record TransactionOperationsFacade(org.springframework.transaction.support.TransactionOperations tx) {
        <T> T execute(java.util.function.Supplier<T> body) {
            return tx.execute(s -> body.get());
        }
    }
}
