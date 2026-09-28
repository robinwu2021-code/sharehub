package ai.neargo.opskit.ticket.store.dao;

import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.ticket.store.TicketJson;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 评论（tkt_comment）。序号在工单内递增；调用方须先锁住工单行（{@link TicketDao#lock}），并发写评论才不会撞序号。
 *
 * @see #insert
 */
public final class CommentDao {

    /** @param kind COMMENT / DEDUPE_HIT / MERGE / SYSTEM；@param visibility PUBLIC / INTERNAL */
    public record Row(String ticketNo, int seq, String visibility, String kind, String author, String onBehalfOf, String body,
            List<Object> attachments, long atMs) {
    }

    private final JdbcClient jdbc;

    public CommentDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return 新评论的序号 */
    public int insert(WorkspaceId ws, String ticketNo, String visibility, String kind, String author, String onBehalfOf, String body,
            List<Map<String, Object>> attachments, long now) {
        int seq = jdbc.sql("SELECT COALESCE(MAX(seq), 0) + 1 FROM tkt_comment WHERE workspace_id = ? AND ticket_no = ?")
                .params(ws.value(), ticketNo).query(Integer.class).single();
        jdbc.sql("INSERT INTO tkt_comment (workspace_id, ticket_no, seq, visibility, kind, author, on_behalf_of, body, attachments, at_ms) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(ws.value(), ticketNo, seq, visibility, kind, author, onBehalfOf, body,
                        attachments == null || attachments.isEmpty() ? null : TicketJson.write(attachments), now)
                .update();
        return seq;
    }

    public List<Row> of(WorkspaceId ws, String ticketNo) {
        return jdbc.sql("SELECT * FROM tkt_comment WHERE workspace_id = ? AND ticket_no = ? ORDER BY seq").params(ws.value(), ticketNo)
                .query((rs, i) -> new Row(rs.getString("ticket_no"), rs.getInt("seq"), rs.getString("visibility"), rs.getString("kind"),
                        rs.getString("author"), rs.getString("on_behalf_of"), rs.getString("body"), TicketJson.readList(rs.getString("attachments")),
                        rs.getLong("at_ms")))
                .list();
    }
}
