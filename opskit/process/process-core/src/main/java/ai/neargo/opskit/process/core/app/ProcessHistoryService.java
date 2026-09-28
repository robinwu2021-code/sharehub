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
import ai.neargo.opskit.commons.security.PermissionGuard;
import ai.neargo.opskit.commons.security.RequiresPermission;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.process.api.definition.CompiledProcess;
import ai.neargo.opskit.process.api.definition.StatusCategory;
import ai.neargo.opskit.process.api.model.SubjectRef;
import ai.neargo.opskit.process.api.model.query.ApprovalRow;
import ai.neargo.opskit.process.api.model.query.HistoryEntry;
import ai.neargo.opskit.process.api.model.query.InstanceQuery;
import ai.neargo.opskit.process.api.model.query.InstanceRow;
import ai.neargo.opskit.process.api.model.query.ProcessStats;
import ai.neargo.opskit.process.api.model.query.SubjectHistory;
import ai.neargo.opskit.process.store.dao.QueryDao;
import ai.neargo.opskit.process.store.dao.QueryDao.InstanceFilter;
import ai.neargo.opskit.process.store.dao.QueryDao.Viewer;
import ai.neargo.opskit.process.store.dao.TransitionLogDao;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 历史 · 实例查询 · 对账（按业务对象）· 流程统计（21/07 O2-10）。只读，不开事务。
 *
 * <p>实例的读可见（历史、查询、对账同一口径，与审批一致）：应用自身 ∨ 参与者 ∨（任务的候选 / 处理人 ∧ 实例范围可见）；
 * 看不见按不存在（404）。统计只出聚合数字，要求 {@code report:view}（应用自身不限）。
 */
public class ProcessHistoryService {

    public static final String REPORT_VIEW = "report:view";
    private static final Set<String> CATEGORIES = java.util.Arrays.stream(StatusCategory.values()).map(Enum::name).collect(Collectors.toSet());

    private final ProcessDefinitionAppService definitions;
    private final QueryDao queries;
    private final TransitionLogDao logs;

    public ProcessHistoryService(ProcessDefinitionAppService definitions, QueryDao queries, TransitionLogDao logs) {
        this.definitions = definitions;
        this.queries = queries;
        this.logs = logs;
    }

    @SystemAction("只读：可见性按参与者 / 任务候选 ∧ 范围过滤，看不见按不存在")
    public List<HistoryEntry> history(Actor actor, String instanceNo) {
        WorkspaceId ws = actor.tenant().workspaceId();
        QueryDao.InstanceRow row = visibleRow(actor, instanceNo);
        Names names = new Names(actor.tenant());
        return logs.ofInstance(ws, instanceNo).stream().map(e -> names.named(entry(e), row.definitionId())).toList();
    }

    /** 单个实例行（实例详情页头），与 instances 的行同形。 */
    @SystemAction("只读：同 history")
    public InstanceRow instance(Actor actor, String instanceNo) {
        return new Names(actor.tenant()).row(visibleRow(actor, instanceNo));
    }

    private QueryDao.InstanceRow visibleRow(Actor actor, String instanceNo) {
        return queries.instances(actor.tenant().workspaceId(), viewer(actor), filter(instanceNo, null, null), 0, 1).stream().findFirst()
                .orElseThrow(() -> new OpsKitException(CommonErrors.NOT_FOUND, "instance not found"));
    }

    @SystemAction("只读：同 history")
    public Page<InstanceRow> instances(Actor actor, InstanceQuery q) {
        PageRequest page = PageRequest.of(q.page(), q.size());
        checkCategories(q.statusCategory());
        WorkspaceId ws = actor.tenant().workspaceId();
        InstanceFilter f = new InstanceFilter(null, q.processCode(), q.statusCategory(), q.status(), q.open(), q.subjectType(), q.subjectRef(),
                q.createdFromMs(), q.createdToMs());
        Viewer v = viewer(actor);
        long total = queries.countInstances(ws, v, f);
        if (total == 0) {
            return Page.empty(page);
        }
        Names names = new Names(actor.tenant());
        return Page.of(queries.instances(ws, v, f, page.offset(), page.size()).stream().map(names::row).toList(), total, page);
    }

    /** 对账：这个业务对象上的全部实例（最新在前），各带审批与完整流转。上限 {@link SubjectHistory#LIMIT}，超了明说。 */
    @SystemAction("只读：同 history（应用自身看全部，成员只看自己可见的）")
    public SubjectHistory subject(Actor actor, String type, String ref) {
        WorkspaceId ws = actor.tenant().workspaceId();
        InstanceFilter f = filter(null, type, ref);
        Viewer v = viewer(actor);
        long total = queries.countInstances(ws, v, f);
        List<QueryDao.InstanceRow> rows = total == 0 ? List.of() : queries.instances(ws, v, f, 0, SubjectHistory.LIMIT);
        List<String> nos = rows.stream().map(QueryDao.InstanceRow::instanceNo).toList();
        Map<String, List<ApprovalRow>> approvals = new HashMap<>();
        Names names = new Names(actor.tenant());
        for (QueryDao.ApprovalRow a : queries.approvalsOfInstances(ws, nos)) {
            approvals.computeIfAbsent(a.instanceNo(), k -> new ArrayList<>()).add(new ApprovalRow(a.approvalNo(), a.instanceNo(), a.processCode(),
                    names.process(a.definitionId()), subject(a.subjectType(), a.subjectRef(), a.subjectDisplay()), a.status(), null, null,
                    a.currentRound(), a.requestedBy(), a.requestedAtMs(), a.finishedAtMs(), null, null));
        }
        Map<String, Long> defOf = rows.stream().collect(Collectors.toMap(QueryDao.InstanceRow::instanceNo, QueryDao.InstanceRow::definitionId));
        Map<String, List<HistoryEntry>> history = logs.ofInstances(ws, nos).stream().map(e -> names.named(entry(e), defOf.get(e.instanceNo())))
                .collect(Collectors.groupingBy(HistoryEntry::instanceNo, LinkedHashMap::new, Collectors.toList()));
        List<SubjectHistory.Item> items = rows.stream().map(r -> new SubjectHistory.Item(names.row(r), approvals.getOrDefault(r.instanceNo(), List.of()),
                history.getOrDefault(r.instanceNo(), List.of()))).toList();
        return new SubjectHistory(type, ref, total, total > rows.size(), items);
    }

    @RequiresPermission("report:view")
    public List<ProcessStats> stats(Actor actor, String processCode, Long fromMs, Long toMs) {
        if (!actor.isAppItself()) {
            PermissionGuard.require(actor, REPORT_VIEW);
        }
        WorkspaceId ws = actor.tenant().workspaceId();
        Map<String, Map<String, Long>> byCategory = new TreeMap<>();
        Map<String, long[]> closed = new HashMap<>();
        for (QueryDao.CategoryStat s : queries.instanceStats(ws, processCode, fromMs, toMs)) {
            byCategory.computeIfAbsent(s.processCode(), k -> zeros(CATEGORIES)).put(s.statusCategory(), s.count());
            long[] c = closed.computeIfAbsent(s.processCode(), k -> new long[2]);
            c[0] += s.closedCount();
            c[1] += s.closedDurationMs();
        }
        Map<String, Map<String, Long>> approvals = new HashMap<>();
        for (QueryDao.ApprovalStat s : queries.approvalStats(ws, processCode, fromMs, toMs)) {
            approvals.computeIfAbsent(s.processCode(), k -> zeros(Set.of("PENDING", "APPROVED", "REJECTED", "WITHDRAWN"))).put(s.status(), s.count());
        }
        List<ProcessStats> out = new ArrayList<>();
        byCategory.forEach((code, cats) -> {
            long total = cats.values().stream().mapToLong(Long::longValue).sum();
            long open = cats.get("NEW") + cats.get("IN_PROGRESS") + cats.get("WAITING");
            long[] c = closed.get(code);
            LocalizedText name = definitions.resolveName(actor.tenant(), code);
            out.add(new ProcessStats(code, name, total, open, cats, c[0] == 0 ? null : c[1] / c[0],
                    approvals.getOrDefault(code, zeros(Set.of("PENDING", "APPROVED", "REJECTED", "WITHDRAWN")))));
        });
        return out;
    }

    // ── 内部 ────────────────────────────────────────────────

    private static Viewer viewer(Actor actor) {
        return new Viewer(actor.isAppItself(), actor.member().map(PrincipalRef::key).orElse(null), CandidateKeys.of(actor), actor.scopes());
    }

    private static InstanceFilter filter(String instanceNo, String subjectType, String subjectRef) {
        return new InstanceFilter(instanceNo, null, null, null, null, subjectType, subjectRef, null, null);
    }

    private static Map<String, Long> zeros(Set<String> keys) {
        Map<String, Long> m = new TreeMap<>();
        keys.forEach(k -> m.put(k, 0L));
        return m;
    }

    private static void checkCategories(List<String> cs) {
        if (cs == null) {
            return;
        }
        List<FieldError> errors = new ArrayList<>();
        for (int i = 0; i < cs.size(); i++) {
            if (!CATEGORIES.contains(cs.get(i))) {
                errors.add(new FieldError("statusCategory[" + i + "]", "opskit.query.unknown_value"));
            }
        }
        if (!errors.isEmpty()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "unknown status category", Map.of(), errors);
        }
    }

    private static HistoryEntry entry(TransitionLogDao.Entry e) {
        return new HistoryEntry(e.entityKind(), e.entityNo(), e.instanceNo(), e.action(), e.fromValue(), e.toValue(), e.seq(), e.actor(), e.onBehalfOf(),
                e.reason(), e.payload(), e.atMs());
    }

    private static SubjectRef subject(String type, String ref, Map<String, Object> display) {
        return type == null ? null : new SubjectRef(type, ref, display);
    }

    /** 一次请求内按定义行缓存名称。 */
    private final class Names {
        private final TenantContext tenant;
        private final Map<Long, CompiledProcess> cache = new HashMap<>();

        Names(TenantContext tenant) {
            this.tenant = tenant;
        }

        LocalizedText process(long definitionId) {
            return compiled(definitionId).definition().name();
        }

        /** INSTANCE 条目补迁移名与状态名（action 是租户定义的迁移代号，前端词表覆盖不了）。 */
        HistoryEntry named(HistoryEntry h, Long definitionId) {
            if (!"INSTANCE".equals(h.entityKind()) || definitionId == null) {
                return h;
            }
            CompiledProcess p = compiled(definitionId);
            LocalizedText action = p.definition().transitions().stream().filter(t -> t.code().equals(h.action())).findFirst()
                    .map(ai.neargo.opskit.process.api.definition.TransitionDef::name).orElse(null);
            return h.withNames(action, statusName(p, h.from()), statusName(p, h.to()));
        }

        private LocalizedText statusName(CompiledProcess p, String code) {
            return code == null ? null : p.definition().statuses().stream().filter(s -> s.code().equals(code)).findFirst()
                    .map(ai.neargo.opskit.process.api.definition.StatusDef::name).orElse(null);
        }

        InstanceRow row(QueryDao.InstanceRow r) {
            CompiledProcess p = compiled(r.definitionId());
            return new InstanceRow(r.instanceNo(), r.processCode(), p.definition().name(), r.processVersion(),
                    subject(r.subjectType(), r.subjectRef(), r.subjectDisplay()), r.status(), p.status(r.status()).name(), r.statusCategory(), r.open(),
                    r.createdBy(), r.createdAtMs(), r.updatedAtMs(), r.closedAtMs());
        }

        private CompiledProcess compiled(long id) {
            return cache.computeIfAbsent(id, k -> definitions.byDefinitionId(tenant, k));
        }
    }
}
