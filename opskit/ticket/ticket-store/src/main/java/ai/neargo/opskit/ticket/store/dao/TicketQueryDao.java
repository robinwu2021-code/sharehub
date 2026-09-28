package ai.neargo.opskit.ticket.store.dao;

import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 工单列表（21/07 O3-8）：筛选 · 字段索引 · 范围标签 · SLA · 排序 · 可见性。列表与计数共用同一段 WHERE。
 * 可见性在 SQL 里判（fail-closed：没有任何一条放行条件就一行也不出）。
 */
public final class TicketQueryDao {

    /**
     * @param all        应用自身或 ticket:view:all
     * @param viewScoped 持 ticket:view:scoped
     * @param anyScope   令牌 scopes 含 *（范围不设限）
     * @param me         {@code member:x}；应用自身为空
     */
    public record Viewer(boolean all, boolean viewScoped, boolean anyScope, Collection<String> scopes, String me) {
        public Viewer {
            scopes = List.copyOf(scopes);
        }
    }

    /** 字段筛选（已按字段类型解析好落在哪一列）。@param op EQ / IN / GT / GTE / LT / LTE */
    public record FieldFilter(String field, String column, String op, List<Object> values) {
    }

    /** 各项为空 = 不限。@param assignees 按受理方的成员属性 / 组展开后的成员集合（空集合 = 一个也不匹配）。 */
    public record Filter(Collection<String> typeCodes, Collection<String> statusCategories, Collection<String> statuses, Collection<String> priorities,
            String assignee, boolean unassigned, String requester, Collection<String> assignees, Collection<String> slaStates, Collection<String> scopes,
            List<FieldFilter> fields, String keyword, Boolean open, Long createdFromMs, Long createdToMs, Long closedFromMs, Long closedToMs,
            Boolean escalated) {
    }

    private static final class Where {
        final StringBuilder sql = new StringBuilder();
        final List<Object> params = new ArrayList<>();

        void and(String clause, Object... ps) {
            sql.append(" AND ").append(clause);
            Collections.addAll(params, ps);
        }

        void in(String column, Collection<?> values) {
            sql.append(" AND ").append(column).append(" IN (").append(marks(values.size())).append(")");
            params.addAll(values);
        }
    }

    private final JdbcClient jdbc;
    private final TicketDao tickets;

    public TicketQueryDao(JdbcClient jdbc, TicketDao tickets) {
        this.jdbc = jdbc;
        this.tickets = tickets;
    }

    /** @param orderBy 已白名单化的排序表达式（调用方从固定集合里选） */
    public List<String> ticketNos(WorkspaceId ws, Viewer v, Filter f, String orderBy, int offset, int limit) {
        Where w = where(ws, v, f);
        List<Object> ps = new ArrayList<>(w.params);
        ps.add(limit);
        ps.add(offset);
        return jdbc.sql("SELECT t.ticket_no FROM tkt_ticket t WHERE t.workspace_id = ?" + w.sql + " ORDER BY " + orderBy + ", t.ticket_no LIMIT ? OFFSET ?")
                .params(ps).query(String.class).list();
    }

    public long count(WorkspaceId ws, Viewer v, Filter f) {
        Where w = where(ws, v, f);
        return jdbc.sql("SELECT COUNT(*) FROM tkt_ticket t WHERE t.workspace_id = ?" + w.sql).params(w.params).query(Long.class).single();
    }

    public List<TicketDao.Row> rows(WorkspaceId ws, List<String> ticketNos) {
        return ticketNos.stream().map(no -> tickets.find(ws, no).orElseThrow()).toList();
    }

    private static Where where(WorkspaceId ws, Viewer v, Filter f) {
        Where w = new Where();
        w.params.add(ws.value());
        if (nonEmpty(f.typeCodes())) {
            w.in("t.type_code", f.typeCodes());
        }
        if (nonEmpty(f.statusCategories())) {
            w.in("t.status_category", f.statusCategories());
        }
        if (nonEmpty(f.statuses())) {
            w.in("t.status", f.statuses());
        }
        if (nonEmpty(f.priorities())) {
            w.in("t.priority", f.priorities());
        }
        if (f.unassigned()) {
            w.and("t.assignee IS NULL");
        } else if (f.assignee() != null) {
            w.and("t.assignee = ?", f.assignee());
        }
        if (f.requester() != null) {
            w.and("t.requester = ?", f.requester());
        }
        if (f.assignees() != null) {
            if (f.assignees().isEmpty()) {
                w.and("1 = 0");
            } else {
                w.in("t.assignee", f.assignees());
            }
        }
        if (nonEmpty(f.slaStates())) {
            w.in("t.sla_state", f.slaStates());
        }
        if (nonEmpty(f.scopes())) {
            w.sql.append(" AND EXISTS (SELECT 1 FROM tkt_ticket_scope s WHERE s.workspace_id = t.workspace_id AND s.ticket_no = t.ticket_no AND s.scope_tag IN (")
                    .append(marks(f.scopes().size())).append("))");
            w.params.addAll(f.scopes());
        }
        for (FieldFilter ff : f.fields()) {
            w.sql.append(" AND EXISTS (SELECT 1 FROM tkt_field_value fv WHERE fv.workspace_id = t.workspace_id AND fv.ticket_no = t.ticket_no AND fv.field_code = ? AND ");
            w.params.add(ff.field());
            String col = "fv." + ff.column();
            switch (ff.op()) {
                case "IN" -> {
                    w.sql.append(col).append(" IN (").append(marks(ff.values().size())).append(")");
                    w.params.addAll(ff.values());
                }
                case "GT", "GTE", "LT", "LTE" -> {
                    w.sql.append(col).append(switch (ff.op()) { case "GT" -> " > ?"; case "GTE" -> " >= ?"; case "LT" -> " < ?"; default -> " <= ?"; });
                    w.params.add(ff.values().get(0));
                }
                default -> {
                    w.sql.append(col).append(" = ?");
                    w.params.add(ff.values().get(0));
                }
            }
            w.sql.append(")");
        }
        if (f.keyword() != null && !f.keyword().isBlank()) {
            String k = f.keyword().strip();
            w.and("(t.ticket_no = ? OR LOWER(t.title) LIKE ? OR t.subject_ref = ?)", k, "%" + escapeLike(k.toLowerCase(java.util.Locale.ROOT)) + "%", k);
        }
        if (f.open() != null) {
            w.and("t.is_open = ?", f.open() ? 1 : 0);
        }
        if (f.createdFromMs() != null) {
            w.and("t.created_at_ms >= ?", f.createdFromMs());
        }
        if (f.createdToMs() != null) {
            w.and("t.created_at_ms < ?", f.createdToMs());
        }
        if (f.closedFromMs() != null) {
            w.and("t.closed_at_ms >= ?", f.closedFromMs());
        }
        if (f.closedToMs() != null) {
            w.and("t.closed_at_ms < ?", f.closedToMs());
        }
        if (f.escalated() != null) {
            w.and("t.escalated = ?", f.escalated() ? 1 : 0);
        }
        visibility(w, v);
        return w;
    }

    /** 可见 = 全部 ∨ 提出人 / 办理人 ∨（view:scoped ∧（范围不设限 ∨ 标签相交））。 */
    private static void visibility(Where w, Viewer v) {
        if (v.all()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        List<Object> ps = new ArrayList<>();
        if (v.me() != null) {
            parts.add("t.requester = ?");
            parts.add("t.assignee = ?");
            ps.add(v.me());
            ps.add(v.me());
        }
        if (v.viewScoped()) {
            if (v.anyScope()) {
                parts.add("1 = 1");
            } else if (!v.scopes().isEmpty()) {
                parts.add("EXISTS (SELECT 1 FROM tkt_ticket_scope vs WHERE vs.workspace_id = t.workspace_id AND vs.ticket_no = t.ticket_no AND vs.scope_tag IN ("
                        + marks(v.scopes().size()) + "))");
                ps.addAll(v.scopes());
            }
        }
        if (parts.isEmpty()) {
            w.and("1 = 0");
            return;
        }
        w.sql.append(" AND (").append(String.join(" OR ", parts)).append(")");
        w.params.addAll(ps);
    }

    private static boolean nonEmpty(Collection<?> c) {
        return c != null && !c.isEmpty();
    }

    private static String marks(int n) {
        return String.join(", ", Collections.nCopies(n, "?"));
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
