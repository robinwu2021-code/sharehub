package ai.neargo.opskit.ticket.core.app;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.platform.client.DirectoryClient;
import ai.neargo.opskit.platform.client.WorkspaceClient;
import ai.neargo.opskit.ticket.api.model.TicketQuery;
import ai.neargo.opskit.ticket.api.model.TicketStats;
import ai.neargo.opskit.ticket.store.dao.TicketQueryDao;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工单统计（21/07 O3-9）：汇总卡 · 按人 · 按组 · 未结数。
 *
 * <p>同口径是结构上保证的：每个数字都由 {@link TicketQueryService} 按它附带的 queryParams 计出 —— 与列表共用同一段 WHERE 与可见性，
 * 统计卡点进去看到的一定就是那些单。「今天」按空间的业务时区算。
 */
public class TicketStatsService {

    private static final List<String> OPEN = List.of("NEW", "IN_PROGRESS", "WAITING");

    private final TicketQueryService queries;
    private final TicketQueryDao dao;
    private final DirectoryClient directory;
    private final WorkspaceClient workspaces;
    private final Clock clock;

    public TicketStatsService(TicketQueryService queries, TicketQueryDao dao, DirectoryClient directory, WorkspaceClient workspaces, Clock clock) {
        this.queries = queries;
        this.dao = dao;
        this.directory = directory;
        this.workspaces = workspaces;
        this.clock = clock;
    }

    /** @param base 公共筛选（类型 · 范围 · 受理方 …），并入每张卡 */
    @SystemAction("只读：可见性同 tickets/query")
    public TicketStats.Summary summary(Actor actor, TicketQuery base) {
        ZoneId zone = workspaces.zone(actor.tenant());
        long today = LocalDate.now(clock.withZone(zone)).atStartOfDay(zone).toInstant().toEpochMilli();
        TicketQuery b = base == null ? empty() : base;
        List<TicketStats.Card> cards = new ArrayList<>();
        cards.add(card(actor, "open", null, with(b, OPEN, null, null, null, null, null)));
        cards.add(card(actor, "inProgress", null, with(b, List.of("IN_PROGRESS"), null, null, null, null, null)));
        cards.add(card(actor, "dueSoon", "warning", with(b, OPEN, List.of("WARNING"), null, null, null, null)));
        cards.add(card(actor, "breached", "danger", with(b, OPEN, List.of("BREACHED"), null, null, null, null)));
        cards.add(card(actor, "unassigned", null, with(b, OPEN, null, "none", null, null, null)));
        cards.add(card(actor, "escalated", "warning", with(b, OPEN, null, null, true, null, null)));
        cards.add(card(actor, "closedToday", null, with(b, List.of("DONE"), null, null, null, false, today)));
        return new TicketStats.Summary(cards, today, zone.getId());
    }

    /** 未结数（角标）；与 tickets/query {statusCategory: 未结三类} 同口径。 */
    @SystemAction("只读：同 summary")
    public long openCount(Actor actor, TicketQuery base) {
        return queries.count(actor, with(base == null ? empty() : base, OPEN, null, null, null, null, null));
    }

    /** 按受理方：每人一行（未派的以 key=null 一行）。区间用 base 的 created / closed 时间筛。 */
    @SystemAction("只读：同 summary")
    public List<TicketStats.Row> byAssignee(Actor actor, TicketQuery base) {
        TicketQuery b = base == null ? empty() : base;
        TicketQueryService.Prepared p = queries.prepare(actor, b);
        List<TicketStats.Row> out = new ArrayList<>();
        for (TicketQueryDao.AssigneeStat s : dao.statsByAssignee(actor.tenant().workspaceId(), p.viewer(), p.filter())) {
            String id = s.assignee() == null ? null : s.assignee().substring("member:".length());
            out.add(row(id, s.total(), s.open(), s.openBreached(), s.closed(), s.closedMet(), s.closedBreached(), s.closedDurationMs(),
                    with(b, null, null, id == null ? "none" : id, null, null, null)));
        }
        return out;
    }

    /** 按组：组内成员名下的单合计（一个人在多个组里时各组都算他）。 */
    @SystemAction("只读：同 summary")
    public List<TicketStats.Row> byGroup(Actor actor, TicketQuery base, List<String> groups) {
        TicketQuery b = base == null ? empty() : base;
        TicketQueryService.Prepared p = queries.prepare(actor, b);
        Map<String, TicketQueryDao.AssigneeStat> byMember = new LinkedHashMap<>();
        dao.statsByAssignee(actor.tenant().workspaceId(), p.viewer(), p.filter()).forEach(s -> {
            if (s.assignee() != null) {
                byMember.put(s.assignee(), s);
            }
        });
        TenantContext t = actor.tenant();
        List<TicketStats.Row> out = new ArrayList<>();
        for (String g : groups == null ? List.<String>of() : groups) {
            Set<String> members = new HashSet<>(directory.membersOf(t, g).stream().map(m -> "member:" + m).toList());
            long total = 0, open = 0, ob = 0, closed = 0, met = 0, br = 0, dur = 0;
            for (String m : members) {
                TicketQueryDao.AssigneeStat s = byMember.get(m);
                if (s != null) {
                    total += s.total();
                    open += s.open();
                    ob += s.openBreached();
                    closed += s.closed();
                    met += s.closedMet();
                    br += s.closedBreached();
                    dur += s.closedDurationMs();
                }
            }
            out.add(row(g, total, open, ob, closed, met, br, dur, withGroup(b, g)));
        }
        return out;
    }

    private TicketStats.Card card(Actor actor, String key, String tone, TicketQuery q) {
        return new TicketStats.Card(key, queries.count(actor, q), tone, q);
    }

    private static TicketStats.Row row(String key, long total, long open, long ob, long closed, long met, long breached, long dur, TicketQuery q) {
        Double rate = met + breached == 0 ? null : (double) met / (met + breached);
        Long avg = closed == 0 ? null : dur / closed;
        return new TicketStats.Row(key, total, open, ob, closed, rate, avg, q);
    }

    private static TicketQuery empty() {
        return new TicketQuery(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** base 上覆写一部分参数（给了的才覆写）；分页清空。 */
    private static TicketQuery with(TicketQuery b, List<String> categories, List<String> sla, String assignee, Boolean escalated, Boolean open,
            Long closedFrom) {
        return new TicketQuery(b.typeCode(), categories != null ? categories : b.statusCategory(), b.status(), b.priority(),
                assignee != null ? assignee : b.assignee(), b.requester(), b.group(), b.assigneeAttr(), sla != null ? sla : b.slaState(), b.scopes(),
                b.fieldFilters(), b.keyword(), open != null ? open : b.open(), escalated != null ? escalated : b.escalated(), b.createdFromMs(),
                b.createdToMs(), closedFrom != null ? closedFrom : b.closedFromMs(), b.closedToMs(), null, null, null);
    }

    private static TicketQuery withGroup(TicketQuery b, String group) {
        return new TicketQuery(b.typeCode(), b.statusCategory(), b.status(), b.priority(), b.assignee(), b.requester(), group, b.assigneeAttr(), b.slaState(),
                b.scopes(), b.fieldFilters(), b.keyword(), b.open(), b.escalated(), b.createdFromMs(), b.createdToMs(), b.closedFromMs(), b.closedToMs(), null,
                null, null);
    }
}
