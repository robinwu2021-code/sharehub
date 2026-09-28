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
import ai.neargo.opskit.process.client.TaskClient;
import ai.neargo.opskit.ticket.api.TicketErrors;
import ai.neargo.opskit.ticket.api.definition.OptionDef;
import ai.neargo.opskit.ticket.api.definition.TicketTypeDef;
import ai.neargo.opskit.ticket.api.model.BulkReassignRequest;
import ai.neargo.opskit.ticket.api.model.BulkReassignResult;
import ai.neargo.opskit.ticket.api.model.CreateTicketRequest;
import ai.neargo.opskit.ticket.api.model.CreateTicketResult;
import ai.neargo.opskit.ticket.api.model.TicketDetail;
import ai.neargo.opskit.ticket.api.model.TicketView;
import ai.neargo.opskit.ticket.api.model.TimelineItem;
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
    public static final String ASSIGN = "ticket:assign";
    public static final String ESCALATE = "ticket:escalate";
    public static final String COMMENT_INTERNAL = "ticket:comment:internal";
    public static final String MERGE = "ticket:merge";
    public static final String WATCHER = "WATCHER";
    /** 数据范围不设限（令牌 scopes:["*"]），与流程层同一约定。 */
    public static final String ANY_SCOPE = "*";
    public static final String REQUESTER = "REQUESTER";
    public static final String ASSIGNEE = "ASSIGNEE";
    private static final String PENDING_INSTANCE = "pending:";

    private final TicketDefinitionAppService definitions;
    private final TicketDao tickets;
    private final FieldIndexDao index;
    private final TicketScopeDao scopes;
    private final CommentDao comments;
    private final ProcessClient processes;
    private final TaskClient taskClient;
    private final TicketDispatcher dispatcher;
    private final BizNoGenerator ids;
    private final Clock clock;
    private final TransactionOperations tx;

    public TicketAppService(TicketDefinitionAppService definitions, TicketDao tickets, FieldIndexDao index, TicketScopeDao scopes, CommentDao comments,
            ProcessClient processes, TaskClient taskClient, TicketDispatcher dispatcher, BizNoGenerator ids, Clock clock, TransactionOperations tx) {
        this.taskClient = taskClient;
        this.dispatcher = dispatcher;
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
                    0, false, null, dedupeKey, dedupeKey, who(actor), now, now, null, 0, req.subject() == null ? null : req.subject().type(),
                    req.subject() == null ? null : req.subject().ref(), req.subject() == null ? null : req.subject().display()));
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
            SubjectRef subject = req.subject() != null ? req.subject() : new SubjectRef("ticket", no, Map.of("title", title));
            InstanceView inst = processes.start(actor, new StartRequest(def.processCode(), subject,
                    processFields(values.values(), req.priority()), participants, tags, def.slaPolicies()));
            tickets.bindInstance(ws, no, inst.instanceNo());
            index.replace(ws, no, def.code(), indexRows(values));
            scopes.replace(ws, no, tags);
            dispatcher.dispatch(t, tickets.find(ws, no).orElseThrow(), def);
            syncSnapshot(t, no);
            return new CreateTicketResult(view(t, tickets.find(ws, no).orElseThrow()), false);
        });
    }

    // ── 读 ──────────────────────────────────────────────────

    /** 可见 = 应用自身 ∨ ticket:view:all ∨ 参与者（提出人 / 办理人 / 关注人 …）∨（ticket:view:scoped ∧ 标签相交）；否则按不存在。 */
    @SystemAction("只读：可见性见方法说明，看不见 404")
    public TicketView get(Actor actor, String ticketNo) {
        TicketDao.Row r = visible(actor, ticketNo);
        return view(actor.tenant(), r);
    }

    /** 详情：可见性同 get；动作按当前操作人算（流程的 who / when）。 */
    @SystemAction("只读：同 get")
    public TicketDetail detail(Actor actor, String ticketNo) {
        TicketDao.Row r = visible(actor, ticketNo);
        TenantContext t = actor.tenant();
        boolean bound = !r.instanceNo().startsWith(PENDING_INSTANCE);
        Map<String, Set<String>> participants = bound ? processes.snapshot(t, r.instanceNo()).map(InstanceView::participants).orElse(Map.of()) : Map.of();
        return new TicketDetail(view(t, r), bound ? processes.slaMetrics(t, r.instanceNo()) : List.of(), participants,
                bound && r.open() ? processes.actions(actor, r.instanceNo()) : List.of(),
                (int) comments.of(t.workspaceId(), ticketNo).stream().filter(c -> "DEDUPE_HIT".equals(c.kind())).count());
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

    // ── 派单操作（O3-5）──────────────────────────────────────

    /**
     * 领取：工单的当前办理任务在池里、且操作人是候选。是候选本身就是办这张单的资格（它就在他的待办里），
     * 所以这里不再叠加工单可见性 —— 由流程按候选判定，非候选得 403。
     */
    @SystemAction("资格由任务候选判定")
    public TicketView claim(Actor actor, String ticketNo) {
        TenantContext t = actor.tenant();
        return tx.execute(s -> {
            TicketDao.Row r = openTicket(t, ticketNo);
            taskClient.claim(actor, openTask(t, r).orElseThrow(() -> new OpsKitException(CommonErrors.STATE_CHANGED, "nothing to claim")));
            syncSnapshot(t, ticketNo);
            return view(t, tickets.find(t.workspaceId(), ticketNo).orElseThrow());
        });
    }

    /** 指派 / 改派：已有办理人时须写原因。没有办理任务（手工派单的类型）时直接建一条给他。 */
    @SystemAction("应用自身或 ticket:assign")
    public TicketView assign(Actor actor, String ticketNo, String to, String reason) {
        requirePermission(actor, ASSIGN);
        TenantContext t = actor.tenant();
        String member = to == null || to.isBlank() ? null : to.startsWith("member:") ? to : "member:" + to;
        if (member == null) {
            throw invalid("to", "opskit.validation.required");
        }
        return tx.execute(s -> {
            TicketDao.Row r = openTicket(t, ticketNo);
            Optional<String> task = openTask(t, r);
            if (task.isPresent()) {
                taskClient.assignTo(TicketDispatcher.internal(t), task.get(), member, reason);
            } else {
                tickets.setCurrentTask(t.workspaceId(), ticketNo,
                        taskClient.createWork(t, r.instanceNo(), List.of(member), null, r.title(), r.priorityOrd(), "TICKET"));
            }
            syncSnapshot(t, ticketNo);
            return view(t, tickets.find(t.workspaceId(), ticketNo).orElseThrow());
        });
    }

    /** 退回抢单池：去掉办理人，回到任务的候选（队列 / 组）里；须写原因。 */
    @SystemAction("应用自身或 ticket:assign")
    public TicketView pool(Actor actor, String ticketNo, String reason) {
        requirePermission(actor, ASSIGN);
        if (reason == null || reason.isBlank()) {
            throw invalid("reason", "opskit.validation.required");
        }
        TenantContext t = actor.tenant();
        return tx.execute(s -> {
            TicketDao.Row r = openTicket(t, ticketNo);
            String task = openTask(t, r).orElseThrow(() -> new OpsKitException(CommonErrors.STATE_CHANGED, "no work task to pool"));
            taskClient.pool(TicketDispatcher.internal(t), task, reason);
            syncSnapshot(t, ticketNo);
            return view(t, tickets.find(t.workspaceId(), ticketNo).orElseThrow());
        });
    }

    /** 外部指定派单的回填（仅应用）。已派出（含超时兜底已派）→ 409。 */
    @SystemAction("仅应用自身：接入方回填受理人")
    public TicketView externalAssignment(Actor actor, String ticketNo, String to) {
        if (!actor.isAppItself()) {
            throw new OpsKitException(CommonErrors.PERMISSION_DENIED, "external assignment is filled by the integrating system");
        }
        TenantContext t = actor.tenant();
        return tx.execute(s -> {
            TicketDao.Row r = openTicket(t, ticketNo);
            if (r.assignee() != null || openTask(t, r).isPresent()) {
                throw new OpsKitException(CommonErrors.STATE_CHANGED, "ticket already dispatched");
            }
            String member = to != null && to.startsWith("member:") ? to : "member:" + to;
            tickets.setCurrentTask(t.workspaceId(), ticketNo, taskClient.createWork(t, r.instanceNo(), List.of(member), null, r.title(), r.priorityOrd(),
                    "TICKET"));
            syncSnapshot(t, ticketNo);
            return view(t, tickets.find(t.workspaceId(), ticketNo).orElseThrow());
        });
    }

    /** 外部指定超时（定时触发，ID = {@code ws:ticketNo:0}）：仍未派出则按兜底策略派。 */
    @SystemAction("定时触发")
    public boolean onAssignTimeout(TenantContext t, String ticketNo) {
        return Boolean.TRUE.equals(tx.execute(s -> tickets.lock(t.workspaceId(), ticketNo).map(r -> {
            boolean done = dispatcher.fallback(t, r, definitions.typeById(t, r.typeDefinitionId())).isPresent();
            if (done) {
                syncSnapshot(t, ticketNo);
            }
            return done;
        }).orElse(false)));
    }

    // ── 升级 · 合并 · 批量改派 · 关注人（O3-6）────────────────────

    /** 升级：打标记、留内部评论；给了 {@code to} 时同时改派给他（接管）。 */
    @SystemAction("应用自身或 ticket:escalate")
    public TicketView escalate(Actor actor, String ticketNo, String to, String reason) {
        requirePermission(actor, ESCALATE);
        if (reason == null || reason.isBlank()) {
            throw invalid("reason", "opskit.validation.required");
        }
        TenantContext t = actor.tenant();
        return tx.execute(s -> {
            TicketDao.Row r = openTicket(t, ticketNo);
            tickets.setEscalated(t.workspaceId(), ticketNo);
            comments.insert(t.workspaceId(), ticketNo, "INTERNAL", "SYSTEM", who(actor), onBehalf(actor), "escalated: " + reason, List.of(), clock.millis());
            if (to != null && !to.isBlank()) {
                String member = to.startsWith("member:") ? to : "member:" + to;
                Optional<String> task = openTask(t, r);
                if (task.isPresent()) {
                    taskClient.assignTo(TicketDispatcher.internal(t), task.get(), member, "escalated: " + reason);
                } else {
                    tickets.setCurrentTask(t.workspaceId(), ticketNo, taskClient.createWork(t, r.instanceNo(), List.of(member), null, r.title(),
                            r.priorityOrd(), "TICKET"));
                }
            }
            syncSnapshot(t, ticketNo);
            return view(t, tickets.find(t.workspaceId(), ticketNo).orElseThrow());
        });
    }

    /**
     * 合并：{@code ticketNo} 并入 {@code into}。不能并入自己；两张都须未结；被并单走类型配置的合并迁移（通常进 CANCELLED 类别）、
     * 记去向；两边各留一条 MERGE 评论。
     */
    @SystemAction("应用自身或 ticket:merge")
    public TicketView merge(Actor actor, String ticketNo, String into, String reason) {
        requirePermission(actor, MERGE);
        if (ticketNo.equals(into)) {
            throw new OpsKitException(TicketErrors.MERGE_INTO_SELF, "cannot merge a ticket into itself", Map.of(), List.of(new FieldError("into",
                    TicketErrors.MERGE_INTO_SELF.key())));
        }
        TenantContext t = actor.tenant();
        return tx.execute(s -> {
            // 按工单号顺序加锁，两张单互相合并的并发请求不会死锁
            String first = ticketNo.compareTo(into) < 0 ? ticketNo : into;
            String second = first.equals(ticketNo) ? into : ticketNo;
            TicketDao.Row a = openTicket(t, first);
            TicketDao.Row b = openTicket(t, second);
            TicketDao.Row source = a.ticketNo().equals(ticketNo) ? a : b;
            TicketTypeDef def = definitions.typeById(t, source.typeDefinitionId());
            if (def.mergeTransition() == null) {
                throw invalid("ticketNo", "opskit.ticket.merge_not_supported");
            }
            long now = clock.millis();
            tickets.setMergedInto(t.workspaceId(), ticketNo, into);
            comments.insert(t.workspaceId(), ticketNo, "PUBLIC", "MERGE", who(actor), onBehalf(actor), "merged into " + into
                    + (reason == null ? "" : ": " + reason), List.of(), now);
            comments.insert(t.workspaceId(), into, "PUBLIC", "MERGE", who(actor), onBehalf(actor), "merged from " + ticketNo
                    + (reason == null ? "" : ": " + reason), List.of(), now);
            processes.fireSystem(t, source.instanceNo(), def.mergeTransition(), who(actor), reason);
            syncSnapshot(t, ticketNo);
            return view(t, tickets.find(t.workspaceId(), ticketNo).orElseThrow());
        });
    }

    /** 批量改派：逐单各自一个事务，单张失败（已关、无权限、并发冲突）记进 failed，不影响其余。 */
    @SystemAction("应用自身或 ticket:assign（逐单再校验）")
    public BulkReassignResult bulkReassign(Actor actor, BulkReassignRequest req) {
        requirePermission(actor, ASSIGN);
        boolean byList = req.ticketNos() != null && !req.ticketNos().isEmpty();
        boolean byFrom = req.from() != null && !req.from().isBlank();
        List<FieldError> errors = new ArrayList<>();
        if (byList == byFrom) {
            errors.add(new FieldError("ticketNos", "opskit.ticket.reassign_pick_one"));
        }
        if (byList && req.ticketNos().size() > BulkReassignRequest.MAX) {
            errors.add(new FieldError("ticketNos", "opskit.validation.too_many"));
        }
        if (req.to() == null || req.to().isBlank()) {
            errors.add(new FieldError("to", "opskit.validation.required"));
        }
        if (req.reason() == null || req.reason().isBlank()) {
            errors.add(new FieldError("reason", "opskit.validation.required"));
        }
        if (!errors.isEmpty()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "invalid bulk reassign", Map.of(), errors);
        }
        List<String> nos;
        int remaining = 0;
        if (byList) {
            nos = req.ticketNos().stream().distinct().toList();
        } else {
            String from = req.from().startsWith("member:") ? req.from() : "member:" + req.from();
            List<String> found = tickets.openOfAssignee(actor.tenant().workspaceId(), from, BulkReassignRequest.MAX);
            nos = found.size() > BulkReassignRequest.MAX ? found.subList(0, BulkReassignRequest.MAX) : found;
            remaining = found.size() > BulkReassignRequest.MAX ? 1 : 0;
        }
        List<String> ok = new ArrayList<>();
        List<BulkReassignResult.Failure> failed = new ArrayList<>();
        for (String no : nos) {
            try {
                assign(actor, no, req.to(), req.reason());
                ok.add(no);
            } catch (OpsKitException e) {
                failed.add(new BulkReassignResult.Failure(no, e.errorKey().key()));
            }
        }
        return new BulkReassignResult(ok, failed, remaining);
    }

    /** 关注人整体替换（实例参与者 WATCHER）：关注人看得见这张单。 */
    @SystemAction("应用自身或 ticket:work")
    public TicketView setWatchers(Actor actor, String ticketNo, List<String> members) {
        requireWork(actor);
        TenantContext t = actor.tenant();
        List<String> refs = members == null ? List.of()
                : members.stream().filter(m -> m != null && !m.isBlank()).map(m -> m.startsWith("member:") ? m : "member:" + m).distinct().toList();
        return tx.execute(s -> {
            TicketDao.Row r = tickets.lock(t.workspaceId(), ticketNo).orElseThrow(TicketAppService::notFound);
            processes.replaceParticipants(TicketDispatcher.internal(t), r.instanceNo(), WATCHER, refs);
            return view(t, r);
        });
    }

    // ── 评论 · 时间线（O3-7）─────────────────────────────────

    /**
     * 写评论。INTERNAL 须 ticket:comment:internal；PUBLIC 须看得见这张单且（ticket:work ∨ 参与者）。
     * 附件只存引用 {@code [{id, name, size?, contentType?}]}，文件在接入方。
     */
    @SystemAction("可见性先判；内部评论另需 ticket:comment:internal")
    public int comment(Actor actor, String ticketNo, String body, String visibility, List<Map<String, Object>> attachments) {
        String vis = visibility == null ? "PUBLIC" : visibility;
        if (!"PUBLIC".equals(vis) && !"INTERNAL".equals(vis)) {
            throw invalid("visibility", "opskit.validation.one_of");
        }
        boolean noBody = body == null || body.isBlank();
        List<Map<String, Object>> files = attachments(attachments);
        if (noBody && files.isEmpty()) {
            throw invalid("body", "opskit.validation.required");
        }
        if (body != null && body.length() > 20_000) {
            throw invalid("body", "opskit.validation.too_long");
        }
        if ("INTERNAL".equals(vis)) {
            requirePermission(actor, COMMENT_INTERNAL);
        }
        TenantContext t = actor.tenant();
        return tx.execute(s -> {
            TicketDao.Row r = visible(actor, ticketNo);
            tickets.lock(t.workspaceId(), ticketNo);
            String me = actor.member().map(PrincipalRef::key).orElse(null);
            boolean participant = me != null && (me.equals(r.requester()) || me.equals(r.assignee()));
            if (!actor.isAppItself() && !actor.productPermissions().contains(WORK) && !participant && !"INTERNAL".equals(vis)) {
                throw new OpsKitException(CommonErrors.PERMISSION_DENIED, "commenting needs " + WORK + " or being a participant");
            }
            return comments.insert(t.workspaceId(), ticketNo, vis, "COMMENT", who(actor), onBehalf(actor), noBody ? null : body.strip(), files,
                    clock.millis());
        });
    }

    /** 时间线：流程历史（建单 · 迁移 · 派单 · 审批 · SLA）+ 工单评论，按时间合成一条流水；内部评论只给有权限的人。 */
    @SystemAction("只读：同 get；内部评论另需 ticket:comment:internal")
    public List<TimelineItem> timeline(Actor actor, String ticketNo) {
        TicketDao.Row r = visible(actor, ticketNo);
        TenantContext t = actor.tenant();
        boolean internal = actor.isAppItself() || actor.productPermissions().contains(COMMENT_INTERNAL);
        List<TimelineItem> raw = new ArrayList<>();
        if (!r.instanceNo().startsWith(PENDING_INSTANCE)) {
            TicketTypeDef def = definitions.typeById(t, r.typeDefinitionId());
            Optional<ai.neargo.opskit.process.api.definition.ProcessDef> pd = processes.processDef(t, def.processCode());
            for (ai.neargo.opskit.process.api.model.query.HistoryEntry h : processes.history(t, r.instanceNo())) {
                TimelineItem it = fromHistory(h, pd.orElse(null));
                if (it != null) {
                    raw.add(it);
                }
            }
        }
        for (CommentDao.Row c : comments.of(t.workspaceId(), ticketNo)) {
            if ("INTERNAL".equals(c.visibility()) && !internal) {
                continue;
            }
            String kind = switch (c.kind()) {
                case "DEDUPE_HIT" -> "DEDUPE";
                case "MERGE" -> "MERGE";
                case "SYSTEM" -> "ESCALATED";
                default -> "COMMENT";
            };
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) (List<?>) c.attachments();
            raw.add(new TimelineItem(0, c.atMs(), kind, c.author(), null, null, null, null, null, null, c.body(), c.visibility(), files, null,
                    "ESCALATED".equals(kind)));
        }
        raw.sort(java.util.Comparator.comparingLong(TimelineItem::atMs));
        List<TimelineItem> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            TimelineItem x = raw.get(i);
            out.add(new TimelineItem(i + 1, x.atMs(), x.kind(), x.actor(), x.action(), x.from(), x.to(), x.actionName(), x.fromName(), x.toName(), x.body(),
                    x.visibility(), x.attachments(), x.reason(), x.hot()));
        }
        return out;
    }

    /** 历史 → 时间线条目；与办单无关的噪音（改字段、任务建 / 完成）不进时间线。 */
    private static TimelineItem fromHistory(ai.neargo.opskit.process.api.model.query.HistoryEntry h, ai.neargo.opskit.process.api.definition.ProcessDef pd) {
        switch (h.entityKind()) {
            case "INSTANCE" -> {
                if ("fields_updated".equals(h.action())) {
                    return null;
                }
                if ("created".equals(h.action())) {
                    return item(h, "CREATED", null, null, statusName(pd, h.to()), false);
                }
                ai.neargo.opskit.commons.i18n.LocalizedText name = pd == null ? null : pd.transitions().stream().filter(x -> x.code().equals(h.action()))
                        .findFirst().map(ai.neargo.opskit.process.api.definition.TransitionDef::name).orElse(null);
                boolean cancelled = pd != null && pd.statuses().stream().anyMatch(st -> st.code().equals(h.to())
                        && st.category() == ai.neargo.opskit.process.api.definition.StatusCategory.CANCELLED);
                return item(h, "TRANSITION", name, statusName(pd, h.from()), statusName(pd, h.to()), cancelled);
            }
            case "TASK" -> {
                boolean assignedOnCreate = "created".equals(h.action()) && h.to() != null && h.to().startsWith("member:");
                if (!assignedOnCreate && !Set.of("claimed", "released", "claim_timeout", "assigned", "reassigned", "pooled").contains(h.action())) {
                    return null;
                }
                return item(h, "ASSIGNED", null, null, null, "claim_timeout".equals(h.action()));
            }
            case "APPROVAL" -> {
                return item(h, "APPROVAL", null, null, null, "reject".equals(h.action()) || "REJECTED".equals(h.to()));
            }
            case "SLA" -> {
                return item(h, "SLA", null, null, null, "BREACHED".equals(h.to()) || "WARNING".equals(h.to()));
            }
            default -> {
                return null;
            }
        }
    }

    private static TimelineItem item(ai.neargo.opskit.process.api.model.query.HistoryEntry h, String kind, ai.neargo.opskit.commons.i18n.LocalizedText name,
            ai.neargo.opskit.commons.i18n.LocalizedText fromName, ai.neargo.opskit.commons.i18n.LocalizedText toName, boolean hot) {
        return new TimelineItem(0, h.atMs(), kind, h.actor(), h.action(), h.from(), h.to(), name, fromName, toName, null, null, null, h.reason(), hot);
    }

    private static ai.neargo.opskit.commons.i18n.LocalizedText statusName(ai.neargo.opskit.process.api.definition.ProcessDef pd, String code) {
        return pd == null || code == null ? null : pd.statuses().stream().filter(s -> s.code().equals(code)).findFirst()
                .map(ai.neargo.opskit.process.api.definition.StatusDef::name).orElse(null);
    }

    private static List<Map<String, Object>> attachments(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> m = raw.get(i);
            if (m == null || !(m.get("id") instanceof String id) || id.isBlank() || !(m.get("name") instanceof String name)) {
                throw invalid("attachments[" + i + "]", "opskit.ticket.expect_attachments");
            }
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", id);
            a.put("name", name);
            if (m.get("size") instanceof Number size) {
                a.put("size", size.longValue());
            }
            if (m.get("contentType") instanceof String ct) {
                a.put("contentType", ct);
            }
            out.add(a);
        }
        return out;
    }

    /** 定时任务只带空间，账户从工单行上取。 */
    @SystemAction("定时触发")
    public Optional<TenantContext> tenantOf(WorkspaceId ws, String ticketNo) {
        return tickets.find(ws, ticketNo).map(r -> new TenantContext(new ai.neargo.opskit.commons.tenancy.AccountId(r.accountId()), ws));
    }

    private Optional<String> openTask(TenantContext t, TicketDao.Row r) {
        return taskClient.openWorkTasks(TicketDispatcher.internal(t), r.instanceNo()).stream().map(ai.neargo.opskit.process.api.model.TaskView::taskNo)
                .findFirst();
    }

    private TicketDao.Row openTicket(TenantContext t, String ticketNo) {
        TicketDao.Row r = tickets.lock(t.workspaceId(), ticketNo).orElseThrow(TicketAppService::notFound);
        if (!r.open()) {
            throw new OpsKitException(TicketErrors.TICKET_CLOSED, "ticket is closed");
        }
        return r;
    }

    private static void requirePermission(Actor actor, String perm) {
        if (!actor.isAppItself() && !actor.productPermissions().contains(perm)) {
            throw new OpsKitException(CommonErrors.PERMISSION_DENIED, "needs " + perm);
        }
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
        // 其余参与者（关注人 · 负责人 …）：以实例上的参与者为准
        if (me != null && !r.instanceNo().startsWith(PENDING_INSTANCE) && processes.snapshot(actor.tenant(), r.instanceNo())
                .map(i -> i.participants().values().stream().anyMatch(set -> set.contains(me))).orElse(false)) {
            return r;
        }
        if (actor.productPermissions().contains(VIEW_SCOPED) && (actor.scopes().contains(ANY_SCOPE)
                || scopes.of(actor.tenant().workspaceId(), ticketNo).stream().anyMatch(actor.scopes()::contains))) {
            return r;
        }
        throw notFound();
    }

    /** 行 → 视图（列表用；可见性由调用方的查询判过）。 */
    @SystemAction("列表渲染内部调用：可见性已在查询里判定")
    public TicketView viewOf(TenantContext t, TicketDao.Row r) {
        return view(t, r);
    }

    private TicketView view(TenantContext t, TicketDao.Row r) {
        return new TicketView(r.ticketNo(), r.typeCode(), r.typeVersion(), r.instanceNo(), r.title(), r.description(), r.priority(), r.priorityOrd(),
                r.resolution(), r.fields(), r.requester(), r.assignee(), r.status(), r.statusCategory(), r.open(), r.slaState(), r.slaNextDueAtMs(),
                r.slaStartedAtMs(), r.slaPausedMs(), r.escalated(), r.mergedInto(), r.dedupeKey(), scopes.of(t.workspaceId(), r.ticketNo()), r.createdBy(),
                r.createdAtMs(), r.updatedAtMs(), r.closedAtMs(), r.subjectType() == null ? null : new SubjectRef(r.subjectType(), r.subjectRef(),
                r.subjectDisplay()));
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
