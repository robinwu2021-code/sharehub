package ai.neargo.opskit.ticket.store.dao;

import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 工单范围标签（tkt_ticket_scope）：整体替换。 */
public final class TicketScopeDao {

    private final JdbcClient jdbc;

    public TicketScopeDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void replace(WorkspaceId ws, String ticketNo, Collection<String> tags) {
        jdbc.sql("DELETE FROM tkt_ticket_scope WHERE workspace_id = ? AND ticket_no = ?").params(ws.value(), ticketNo).update();
        for (String tag : new TreeSet<>(tags)) {
            jdbc.sql("INSERT INTO tkt_ticket_scope (workspace_id, ticket_no, scope_tag) VALUES (?, ?, ?)").params(ws.value(), ticketNo, tag).update();
        }
    }

    public Set<String> of(WorkspaceId ws, String ticketNo) {
        return new TreeSet<>(jdbc.sql("SELECT scope_tag FROM tkt_ticket_scope WHERE workspace_id = ? AND ticket_no = ?")
                .params(ws.value(), ticketNo).query(String.class).list());
    }
}
