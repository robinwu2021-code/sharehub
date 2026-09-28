package ai.neargo.opskit.ticket.core.app;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.query.Page;
import ai.neargo.opskit.commons.query.PageRequest;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.platform.client.DirectoryClient;
import ai.neargo.opskit.ticket.api.TicketErrors;
import ai.neargo.opskit.ticket.api.definition.FieldDef;
import ai.neargo.opskit.ticket.api.definition.TicketTypeDef;
import ai.neargo.opskit.ticket.api.model.TicketQuery;
import ai.neargo.opskit.ticket.api.model.TicketView;
import ai.neargo.opskit.ticket.store.dao.TicketDao;
import ai.neargo.opskit.ticket.store.dao.TicketQueryDao;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工单列表（21/07 O3-8）：每个筛选参数一条校验；可见性在 SQL 里 fail-closed；列表与计数同口径（统计卡回填同一组参数就能得到同一组单）。
 */
public class TicketQueryService {

    public static final int ATTR_LIMIT = 5000;
    private static final Set<String> CATEGORIES = Set.of("NEW", "IN_PROGRESS", "WAITING", "DONE", "CANCELLED");
    private static final Set<String> SLA_STATES = Set.of("RUNNING", "WARNING", "PAUSED", "BREACHED", "MET");
    private static final Set<String> STR_OPS = Set.of("EQ", "IN");
    private static final Set<String> NUM_OPS = Set.of("EQ", "IN", "GT", "GTE", "LT", "LTE");
    /** 排序白名单：参数值 → SQL 片段（只从这里取，不拼用户输入）。 */
    private static final Map<String, String> SORTS = Map.of(
            "createdAt", "t.created_at_ms ASC", "-createdAt", "t.created_at_ms DESC",
            "updatedAt", "t.updated_at_ms ASC", "-updatedAt", "t.updated_at_ms DESC",
            "priority", "CASE WHEN t.priority_ord IS NULL THEN 1 ELSE 0 END, t.priority_ord ASC, t.created_at_ms ASC",
            "slaDue", "CASE WHEN t.sla_next_due_at_ms IS NULL THEN 1 ELSE 0 END, t.sla_next_due_at_ms ASC");

    private final TicketQueryDao queries;
    private final TicketDefinitionAppService definitions;
    private final TicketAppService tickets;
    private final DirectoryClient directory;

    public TicketQueryService(TicketQueryDao queries, TicketDefinitionAppService definitions, TicketAppService tickets, DirectoryClient directory) {
        this.queries = queries;
        this.definitions = definitions;
        this.tickets = tickets;
        this.directory = directory;
    }

    /** 校验并解析好的查询（列表、计数、统计共用 —— 统计卡与列表同口径的来源）。 */
    record Prepared(TicketQueryDao.Viewer viewer, TicketQueryDao.Filter filter, String order) {
    }

    @SystemAction("只读：可见性在查询里 fail-closed 判定")
    public Page<TicketView> query(Actor actor, TicketQuery q) {
        PageRequest page = PageRequest.of(q.page(), q.size());
        TenantContext t = actor.tenant();
        Prepared p = prepare(actor, q);
        long total = queries.count(t.workspaceId(), p.viewer(), p.filter());
        if (total == 0) {
            return Page.empty(page);
        }
        List<TicketDao.Row> rows = queries.rows(t.workspaceId(), queries.ticketNos(t.workspaceId(), p.viewer(), p.filter(), p.order(), page.offset(),
                page.size()));
        return Page.of(rows.stream().map(r -> tickets.viewOf(t, r)).toList(), total, page);
    }

    /** 与 query 的 total 完全同口径。 */
    @SystemAction("只读：同 query")
    public long count(Actor actor, TicketQuery q) {
        Prepared p = prepare(actor, q);
        return queries.count(actor.tenant().workspaceId(), p.viewer(), p.filter());
    }

    Prepared prepare(Actor actor, TicketQuery q) {
        TenantContext t = actor.tenant();
        List<FieldError> errors = new ArrayList<>();
        check(q.statusCategory(), CATEGORIES, "statusCategory", errors);
        check(q.slaState(), SLA_STATES, "slaState", errors);
        String order = SORTS.get(q.sort() == null ? "-createdAt" : q.sort());
        if (order == null) {
            errors.add(new FieldError("sort", "opskit.query.unknown_value"));
        }
        String me = actor.member().map(PrincipalRef::key).orElse(null);
        String assignee = null;
        boolean unassigned = false;
        if ("none".equals(q.assignee())) {
            unassigned = true;
        } else if ("me".equals(q.assignee())) {
            assignee = me == null ? "-" : me;
        } else if (q.assignee() != null && !q.assignee().isBlank()) {
            assignee = member(q.assignee());
        }
        List<TicketQueryDao.FieldFilter> fields = fieldFilters(t, q, errors);
        if (!errors.isEmpty()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "invalid ticket query", Map.of(), errors);
        }
        Collection<String> assignees = assigneeSet(t, q);
        TicketQueryDao.Filter f = new TicketQueryDao.Filter(q.typeCode(), q.statusCategory(), q.status(), q.priority(), assignee, unassigned,
                q.requester() == null ? null : "me".equals(q.requester()) ? (me == null ? "-" : me) : member(q.requester()), assignees, q.slaState(),
                q.scopes(), fields, q.keyword(), q.open(), q.createdFromMs(), q.createdToMs(), q.closedFromMs(), q.closedToMs(), q.escalated());
        TicketQueryDao.Viewer v = new TicketQueryDao.Viewer(actor.isAppItself() || actor.productPermissions().contains(TicketAppService.VIEW_ALL),
                actor.productPermissions().contains(TicketAppService.VIEW_SCOPED), actor.scopes().contains(TicketAppService.ANY_SCOPE), actor.scopes(), me);
        return new Prepared(v, f, order);
    }

    /** 组 / 成员属性 → 受理方集合（两者都给时取交集）；属性匹配太多 → 422，提示改用组筛选。 */
    private Collection<String> assigneeSet(TenantContext t, TicketQuery q) {
        Set<String> out = null;
        if (q.group() != null && !q.group().isBlank()) {
            out = new LinkedHashSet<>(directory.membersOf(t, q.group()).stream().map(m -> "member:" + m).toList());
        }
        if (q.assigneeAttr() != null && q.assigneeAttr().key() != null && q.assigneeAttr().values() != null && !q.assigneeAttr().values().isEmpty()) {
            List<String> ids = directory.membersWithAttr(t, q.assigneeAttr().key(), q.assigneeAttr().values(), ATTR_LIMIT);
            if (ids.size() > ATTR_LIMIT) {
                throw new OpsKitException(TicketErrors.FILTER_TOO_BROAD, "assignee attribute matches more than " + ATTR_LIMIT + " members; filter by group",
                        Map.of("limit", ATTR_LIMIT), List.of(new FieldError("assigneeAttr", TicketErrors.FILTER_TOO_BROAD.key())));
            }
            Set<String> byAttr = new LinkedHashSet<>(ids.stream().map(m -> "member:" + m).toList());
            if (out == null) {
                out = byAttr;
            } else {
                out.retainAll(byAttr);
            }
        }
        return out;
    }

    /** 字段筛选：须只选一个类型（字段由类型定义）；字段须可筛选；按字段类型决定落在哪一列、允许哪些操作。 */
    private List<TicketQueryDao.FieldFilter> fieldFilters(TenantContext t, TicketQuery q, List<FieldError> errors) {
        List<TicketQueryDao.FieldFilter> out = new ArrayList<>();
        if (q.fieldFilters() == null || q.fieldFilters().isEmpty()) {
            return out;
        }
        if (q.typeCode() == null || q.typeCode().size() != 1) {
            errors.add(new FieldError("fieldFilters", "opskit.ticket.field_filter_needs_one_type"));
            return out;
        }
        TicketTypeDef def = definitions.resolveType(t, q.typeCode().get(0)).map(TicketDefinitionAppService.ResolvedType::def).orElse(null);
        if (def == null) {
            errors.add(new FieldError("typeCode[0]", "opskit.ticket.unknown_type"));
            return out;
        }
        for (int i = 0; i < q.fieldFilters().size(); i++) {
            TicketQuery.FieldFilter ff = q.fieldFilters().get(i);
            String p = "fieldFilters[" + i + "]";
            FieldDef f = ff.field() == null ? null : def.field(ff.field()).orElse(null);
            if (f == null || !f.filterable()) {
                errors.add(new FieldError(p + ".field", "opskit.ticket.field_not_filterable"));
                continue;
            }
            String op = ff.op() == null ? "EQ" : ff.op();
            String column = switch (f.type()) {
                case NUMBER, MONEY -> "v_num";
                case DATETIME -> "v_time";
                default -> "v_str";
            };
            if (!("v_str".equals(column) ? STR_OPS : NUM_OPS).contains(op)) {
                errors.add(new FieldError(p + ".op", "opskit.query.unknown_value"));
                continue;
            }
            List<Object> raw = "IN".equals(op) ? (ff.value() instanceof Collection<?> c ? new ArrayList<>(c) : null)
                    : ff.value() == null ? null : List.of(ff.value());
            if (raw == null || raw.isEmpty()) {
                errors.add(new FieldError(p + ".value", "opskit.validation.required"));
                continue;
            }
            List<Object> values = new ArrayList<>();
            for (Object x : raw) {
                Object v = value(f, x);
                if (v == null) {
                    errors.add(new FieldError(p + ".value", "opskit.ticket.field_invalid"));
                    break;
                }
                values.add(v);
            }
            if (values.size() == raw.size()) {
                out.add(new TicketQueryDao.FieldFilter(f.code(), column, op, values));
            }
        }
        return out;
    }

    /** 筛选值 → 索引列里的形状（与 FieldValues 写索引时一致）。 */
    private static Object value(FieldDef f, Object x) {
        try {
            return switch (f.type()) {
                case NUMBER, MONEY -> x instanceof Number || x instanceof String ? new BigDecimal(String.valueOf(x)) : null;
                case DATETIME -> x instanceof Number n ? n.longValue() : null;
                case MEMBER -> x instanceof String s && !s.isBlank() ? member(s) : null;
                case REF -> x instanceof Map<?, ?> m && m.get("type") != null && m.get("ref") != null ? m.get("type") + ":" + m.get("ref")
                        : x instanceof String s && s.contains(":") ? s : null;
                default -> x instanceof String s ? s : null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void check(List<String> values, Set<String> allowed, String path, List<FieldError> errors) {
        if (values == null) {
            return;
        }
        for (int i = 0; i < values.size(); i++) {
            if (!allowed.contains(values.get(i))) {
                errors.add(new FieldError(path + "[" + i + "]", "opskit.query.unknown_value"));
            }
        }
    }

    private static String member(String raw) {
        return raw.startsWith("member:") ? raw : "member:" + raw;
    }
}
