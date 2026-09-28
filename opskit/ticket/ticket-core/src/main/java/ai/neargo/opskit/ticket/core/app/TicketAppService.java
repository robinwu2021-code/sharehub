package ai.neargo.opskit.ticket.core.app;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.jdbc.ConditionalUpdate;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.process.api.model.InstanceView;
import ai.neargo.opskit.process.api.model.StartRequest;
import ai.neargo.opskit.process.api.model.SubjectRef;
import ai.neargo.opskit.process.api.model.TransitionRequest;
import ai.neargo.opskit.process.client.ProcessClient;
import ai.neargo.opskit.ticket.api.TicketErrors;
import ai.neargo.opskit.ticket.api.definition.OptionDef;
import ai.neargo.opskit.ticket.api.definition.TicketTypeDef;
import ai.neargo.opskit.ticket.api.model.CreateTicketRequest;
import ai.neargo.opskit.ticket.api.model.CreateTicketResult;
import ai.neargo.opskit.ticket.api.model.TicketView;
import ai.neargo.opskit.ticket.api.model.UpdateTicketRequest;
import ai.neargo.opskit.ticket.core.domain.FieldValues;
import ai.neargo.opskit.ticket.core.domain.SlaRollup;
import ai.neargo.opskit.ticket.store.dao.CommentDao;
import ai.neargo.opskit.ticket.store.dao.FieldIndexDao;
import ai.neargo.opskit.ticket.store.dao.TicketDao;
import ai.neargo.opskit.ticket.store.dao.TicketScopeDao;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 工单（21/07 O3-3 · O3-4 · 21/02 §8.6）：建单（字段校验 → 去重占位 → 写工单与索引 → 派生标签 → 启动实例）· 改内容 · 流转 · 快照同步。
 *
 * <p>工单没有自己的状态机：状态只经流程实例迁移，工单表存只读快照，由 {@link #syncSnapshot} 在同一事务里同步（{@code TicketObserver}）。
 */
public class TicketAppService {

    public static final String VIEW_ALL = "ticket:view:all";
    public static final String VIEW_SCOPED = "ticket:view:scoped";
    public static final String CREATE = "ticket:create";
    public static final String WORK = "ticket:work";
    public static final String REQUESTER = "REQUESTER";
    public static final String ASSIGNEE = "ASSIGNEE";
    private static final String PENDING_INSTANCE = "pending:";

    private final TicketDefinitionAppService definitions;
    private final TicketDao tickets;
    private final FieldIndexDao index;
    private final TicketScopeDao scopes;
    private final CommentDao comments;
    private final ProcessClient processes;
    private final BizNoGenerator ids;
    private final Clock clock;
    private final TransactionOperations tx;

    public TicketAppService(TicketDefinitionAppService definitions, TicketDao tickets, FieldIndexDao index, TicketScopeDao scopes, CommentDao comments,
            ProcessClient processes, BizNoGenerator ids, Clock clock, TransactionOperations tx) {
        this.definitions = definitions;
        this.tickets = tickets;
        this.index = index;
        this.scopes = scopes;
        this.comments = comments;
        this.processes = processes;
        this.ids = ids;
        this.clock = clock;
        this.tx = tx;
    }

    // ── 建单 ────────────────────────────────────────────────

    /**
     * 建单。同一去重键已有未结单时不新建，附加到那张单（写一条 DEDUPE_HIT 内部评论）并返回 {@code attached=true}；并发同键也只有一张。
     */
    @SystemAction("成员需 ticket:create；应用自身不限")
    public CreateTicketResult create(Actor actor, CreateTicketRequest req) {
        if (!actor.isAppItself() && !actor.productPermissions().contains(CREATE)) {
            throw new OpsKitException(CommonErrors.PERMISSION_DENIED, "creating tickets needs " + CREATE);
        }
        TenantContext t = actor.tenant();
        TicketDefinitionAppService.ResolvedType type = definitions.resolveType(t, req.typeCode())
                .orElseThrow(() -> invalid("typeCode", "opskit.ticket.unknown_type"));
        if (!type.enabled()) {
            throw new OpsKitException(TicketErrors.TYPE_DISABLED, "ticket type disabled: " + req.typeCode());
        }
        TicketTypeDef def = type.def();
        List<FieldError> errors = new ArrayList<>();
        String title = title(req.title(), errors);
        Integer priorityOrd = priority(t, def, req.priority(), null, errors);
        String requester = requester(actor, req.requester(), errors);
        FieldValues.Result values = FieldValues.normalize(def, definitions.fieldOptions(t), req.fields(), FieldValues.Mode.CREATE, Map.of(), errors);
        if (!errors.isEmpty()) {
            throw new OpsKitException(TicketErrors.FIELD_INVALID, "invalid ticket", Map.of("typeCode", def.code()), errors);
        }
        String dedupeKey = req.dedupeKey() != null && !req.dedupeKey().isBlank() ? truncate(req.dedupeKey().strip(), 160) : values.dedupeKey();
        return tx.execute(s -> {
            WorkspaceId ws = t.workspaceId();
            long now = clock.millis();
            String no = ids.next(def.numberPrefix() == null ? "TK" : def.numberPrefix());
            tickets.insert(ws, new TicketDao.Row(t.accountId().value(), ws.value(), no, type.definitionId(), def.code(), type.version(), PENDING_INSTANCE + no,
                    title, req.description(), req.priority(), priorityOrd, null, values.values(), requester, null, null, "-", "NEW", true, null, null, null,
                    0, false, null, dedupeKey, dedupeKey, who(actor), now, now, null, 0));
            if (dedupeKey != null) {
                TicketDao.Row holder = tickets.lockByDedupeOpenKey(ws, dedupeKey).orElseThrow();
                if (!holder.ticketNo().equals(no)) {
                    // 去重命中：不建新单，给既有单留痕
                    comments.insert(ws, holder.ticketNo(), "INTERNAL", "DEDUPE_HIT", who(actor), onBehalf(actor),
                            "duplicate report by " + (requester == null ? who(actor) : requester) + ": " + title, List.of(), now);
                    return new CreateTicketResult(view(t, holder), true);
                }
            }
            Set<String> tags = new LinkedHashSet<>(values.scopes());
            tags.addAll(req.scopes());
            Map<String, List<String>> participants = new HashMap<>();
            if (requester != null) {
                participants.put(REQUESTER, List.of(requester));
            }
            InstanceView inst = processes.start(actor, new StartRequest(def.processCode(), new SubjectRef("ticket", no, Map.of("title", title)),
                    processFields(values.values(), req.priority()), participants, tags, def.slaPolicies()));
            tickets.bindInstance(ws, no, inst.instanceNo());
            index.replace(ws, no, def.code(), indexRows(values));
            scopes.replace(ws, no, tags);
            syncSnapshot(t, no);
            return new CreateTicketResult(view(t, tickets.find(ws, no).orElseThrow()), false);
        });
    }

    // ── 读 ──────────────────────────────────────────────────

    /** 可见 = 应用自身 ∨ ticket:view:all ∨ 提出人 / 办理人 ∨（ticket:view:scoped ∧ 标签相交）；否则按不存在（O3-8 补齐参与者全集）。 */
    @SystemAction("只读：可见性见方法说明，看不见 404")
    public TicketView get(Actor actor, String ticketNo) {
        TicketDao.Row r = visible(actor, ticketNo);
        return view(actor.tenant(), r);
    }

    // ── 改内容 · 流转 ────────────────────────────────────────

    /** 改标题 / 描述 / 优先级 / 字段；字段按建单时的类型版本校验。字段变化同步给流程（SLA 按新优先级重算）与范围标签。 */
    @SystemAction("应用自身或 ticket:work")
    public TicketView update(Actor actor, String ticketNo, UpdateTicketRequest req) {
        requireWork(actor);
        TenantContext t = actor.tenant();
        return tx.execute(s -> {
            WorkspaceId ws = t.workspaceId();
            TicketDao.Row r = tickets.lock(ws, ticketNo).orElseThrow(() -> notFound());
            if (!r.open()) {
                throw new OpsKitException(TicketErrors.TICKET_CLOSED, "ticket is closed");
            }
            TicketTypeDef def = definitions.typeById(t, r.typeDefinitionId());
            List<FieldError> errors = new ArrayList<>();
            String title = req.title() == null ? r.title() : title(req.title(), errors);
            String priority = req.priority() == null ? r.priority() : req.priority();
            Integer priorityOrd = req.priority() == null ? r.priorityOrd() : priority(t, def, req.priority(), r.priority(), errors);
            FieldValues.Result values = FieldValues.normalize(def, definitions.fieldOptions(t), req.fields() == null ? Map.of() : req.fields(),
                    FieldValues.Mode.UPDATE, r.fields(), errors);
            if (!errors.isEmpty()) {
                throw new OpsKitException(TicketErrors.FIELD_INVALID, "invalid ticket", Map.of("ticketNo", ticketNo), errors);
            }
            long now = clock.millis();
            ConditionalUpdate.requireExactlyOne(tickets.updateContent(ws, ticketNo, r.version(), title,
                    req.description() == null ? r.description() : req.description(), priority, priorityOrd, values.values(), now), "ticket " + ticketNo);
            index.replace(ws, ticketNo, def.code(), indexRows(values));
            // 标签 = 新派生的 ∪（原标签 − 原派生的）：显式给过的标签不因改字段丢失
            Set<String> oldDerived = FieldValues.normalize(def, definitions.fieldOptions(t), Map.of(), FieldValues.Mode.UPDATE, r.fields(), new ArrayList<>())
                    .scopes();
            Set<String> tags = new LinkedHashSet<>(values.scopes());
            scopes.of(ws, ticketNo).stream().filter(x -> !oldDerived.contains(x)).forEach(tags::add);
            scopes.replace(ws, ticketNo, tags);
            Actor internal = internal(t);
            processes.replaceScopes(internal, r.instanceNo(), tags);
            processes.updateFields(internal, r.instanceNo(), processFields(values.values(), priority));
            syncSnapshot(t, ticketNo);
            return view(t, tickets.find(ws, ticketNo).orElseThrow());
        });
    }

    /** 流转 = 走流程迁移（授权由流程定义的 who 判定）；resolution 须是类型的解决结果选项。 */
    @SystemAction("授权由流程定义判定；可见性先判")
    public TicketView transition(Actor actor, String ticketNo, String code, String comment, String resolution) {
        TenantContext t = actor.tenant();
        return tx.execute(s -> {
            TicketDao.Row r = visible(actor, ticketNo);
            if (resolution != null) {
                TicketTypeDef def = definitions.typeById(t, r.typeDefinitionId());
                boolean ok = definitions.optionsOf(t, def.resolutionOptions()).stream().anyMatch(o -> o.code().equals(resolution) && o.enabled());
                if (!ok) {
                    throw invalid("resolution", "opskit.ticket.unknown_option");
                }
                tickets.setResolution(t.workspaceId(), ticketNo, resolution);
            }
            processes.transition(actor, r.instanceNo(), code, new TransitionRequest(Map.of(), comment));
            syncSnapshot(t, ticketNo);
            return view(t, tickets.find(t.workspaceId(), ticketNo).orElseThrow());
        });
    }

    // ── 快照同步（O3-4）──────────────────────────────────────

    /** 实例变化 → 工单快照。实例还没绑定（建单途中）时什么也不做 —— 建单收尾会自己同步一次。 */
    @SystemAction("流程变化同事务回调")
    public void syncByInstance(TenantContext t, String instanceNo) {
        tickets.findByInstance(t.workspaceId(), instanceNo).ifPresent(r -> syncSnapshot(t, r.ticketNo()));
    }

    @SystemAction("流程变化同事务回调")
    public void syncSnapshot(TenantContext t, String ticketNo) {
        TicketDao.Row r = tickets.find(t.workspaceId(), ticketNo).orElseThrow();
        if (r.instanceNo().startsWith(PENDING_INSTANCE)) {
            return;
        }
        InstanceView inst = processes.snapshot(t, r.instanceNo()).orElseThrow(() -> new IllegalStateException("ticket " + ticketNo + " lost its instance"));
        SlaRollup.Rollup sla = SlaRollup.of(processes.slaMetrics(t, r.instanceNo()));
        String assignee = inst.participants().getOrDefault(ASSIGNEE, Set.of()).stream().findFirst().orElse(null);
        tickets.updateSnapshot(t.workspaceId(), ticketNo, new TicketDao.Snapshot(inst.status(), inst.statusCategory().name(), inst.open(), sla.state(),
                sla.nextDueAtMs(), sla.startedAtMs(), sla.pausedMs(), assignee), clock.millis());
    }

    // ── 内部 ────────────────────────────────────────────────

    private TicketDao.Row visible(Actor actor, String ticketNo) {
        TicketDao.Row r = tickets.find(actor.tenant().workspaceId(), ticketNo).orElseThrow(TicketAppService::notFound);
        if (actor.isAppItself() || actor.productPermissions().contains(VIEW_ALL)) {
            return r;
        }
        String me = actor.member().map(PrincipalRef::key).orElse(null);
        if (me != null && (me.equals(r.requester()) || me.equals(r.assignee()))) {
            return r;
        }
        if (actor.productPermissions().contains(VIEW_SCOPED) && scopes.of(actor.tenant().workspaceId(), ticketNo).stream().anyMatch(actor.scopes()::contains)) {
            return r;
        }
        throw notFound();
    }

    private TicketView view(TenantContext t, TicketDao.Row r) {
        return new TicketView(r.ticketNo(), r.typeCode(), r.typeVersion(), r.instanceNo(), r.title(), r.description(), r.priority(), r.priorityOrd(),
                r.resolution(), r.fields(), r.requester(), r.assignee(), r.status(), r.statusCategory(), r.open(), r.slaState(), r.slaNextDueAtMs(),
                r.slaStartedAtMs(), r.slaPausedMs(), r.escalated(), r.mergedInto(), r.dedupeKey(), scopes.of(t.workspaceId(), r.ticketNo()), r.createdBy(),
                r.createdAtMs(), r.updatedAtMs(), r.closedAtMs());
    }

    /** 传给流程的字段：全部字段值 + priority（SLA 目标矩阵常按它匹配）。 */
    private static Map<String, Object> processFields(Map<String, Object> values, String priority) {
        Map<String, Object> m = new LinkedHashMap<>(values);
        if (priority != null) {
            m.put("priority", priority);
        }
        return m;
    }

    private static List<FieldIndexDao.Value> indexRows(FieldValues.Result r) {
        return r.index().stream().map(v -> new FieldIndexDao.Value(v.field(), v.vStr(), v.vNum(), v.vTime())).toList();
    }

    private static String title(String raw, List<FieldError> errors) {
        if (raw == null || raw.isBlank()) {
            errors.add(new FieldError("title", "opskit.validation.required"));
            return null;
        }
        if (raw.strip().length() > 256) {
            errors.add(new FieldError("title", "opskit.validation.too_long"));
        }
        return raw.strip();
    }

    /** 优先级 → 序号（在选项列表里的位置，排序用）；新选的须启用（原值已停用时保持不报错）。 */
    private Integer priority(TenantContext t, TicketTypeDef def, String code, String previous, List<FieldError> errors) {
        if (code == null) {
            return null;
        }
        List<OptionDef> opts = definitions.optionsOf(t, def.priorityOptions());
        for (int i = 0; i < opts.size(); i++) {
            OptionDef o = opts.get(i);
            if (o.code().equals(code)) {
                if (!o.enabled() && !code.equals(previous)) {
                    errors.add(new FieldError("priority", "opskit.ticket.option_disabled"));
                }
                return i;
            }
        }
        errors.add(new FieldError("priority", "opskit.ticket.unknown_option"));
        return null;
    }

    /** 提出人：给了用给的（应用代提时），否则操作人本人。成员只能以自己的名义提。 */
    private static String requester(Actor actor, String raw, List<FieldError> errors) {
        String me = actor.member().map(PrincipalRef::key).orElse(null);
        if (raw == null || raw.isBlank()) {
            return me;
        }
        String ref = raw.startsWith("member:") ? raw : "member:" + raw;
        if (!actor.isAppItself() && !ref.equals(me)) {
            errors.add(new FieldError("requester", "opskit.ticket.requester_must_be_self"));
        }
        return ref;
    }

    private static void requireWork(Actor actor) {
        if (!actor.isAppItself() && !actor.productPermissions().contains(WORK)) {
            throw new OpsKitException(CommonErrors.PERMISSION_DENIED, "editing tickets needs " + WORK);
        }
    }

    /** 调流程的「内部应用身份」：工单模块已判完权限，改字段 / 标签不再让流程按成员角色拦。 */
    private static Actor internal(TenantContext t) {
        return new Actor(t, Optional.empty(), Optional.of("opskit-ticket"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    private static String who(Actor a) {
        return a.member().map(PrincipalRef::key).orElseGet(() -> "app:" + a.app().orElse("?"));
    }

    private static String onBehalf(Actor a) {
        return a.member().isPresent() ? a.app().map(x -> "app:" + x).orElse(null) : null;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static OpsKitException invalid(String path, String error) {
        return new OpsKitException(TicketErrors.FIELD_INVALID, "invalid " + path, Map.of(), List.of(new FieldError(path, error)));
    }

    private static OpsKitException notFound() {
        return new OpsKitException(CommonErrors.NOT_FOUND, "ticket not found");
    }
}
