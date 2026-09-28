package ai.neargo.opskit.process.store.dao;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.process.store.ProcessJson;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 流转日志（proc_transition_log），与变化同事务写入，只增不改。 */
public final class TransitionLogDao {

    public record Entry(String accountId, String entityKind, String entityNo, String instanceNo, String subjectType, String subjectRef,
            String action, String fromValue, String toValue, Integer seq, String actor, String onBehalfOf, String reason,
            Map<String, Object> payload, long atMs) {
    }

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public TransitionLogDao(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    public void insert(WorkspaceId ws, Entry e) {
        jdbc.sql("INSERT INTO proc_transition_log (account_id, workspace_id, entity_kind, entity_no, instance_no, subject_type, subject_ref, action, "
                        + "from_value, to_value, seq, actor, on_behalf_of, reason, payload, at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + dialect.jsonParam() + ", ?)")
                .params(e.accountId(), ws.value(), e.entityKind(), e.entityNo(), e.instanceNo(), e.subjectType(), e.subjectRef(), e.action(),
                        e.fromValue(), e.toValue(), e.seq(), e.actor(), e.onBehalfOf(), e.reason(),
                        e.payload() == null || e.payload().isEmpty() ? null : ProcessJson.write(e.payload()), e.atMs())
                .update();
    }

    public List<Entry> ofInstances(WorkspaceId ws, java.util.Collection<String> instanceNos) {
        if (instanceNos.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM proc_transition_log WHERE workspace_id = :ws AND instance_no IN (:nos) ORDER BY at_ms, id")
                .param("ws", ws.value()).param("nos", instanceNos)
                .query((rs, i) -> new Entry(rs.getString("account_id"), rs.getString("entity_kind"), rs.getString("entity_no"),
                        rs.getString("instance_no"), rs.getString("subject_type"), rs.getString("subject_ref"), rs.getString("action"),
                        rs.getString("from_value"), rs.getString("to_value"), (Integer) rs.getObject("seq", Integer.class), rs.getString("actor"),
                        rs.getString("on_behalf_of"), rs.getString("reason"), ProcessJson.readMap(rs.getString("payload")), rs.getLong("at_ms")))
                .list();
    }

    public List<Entry> ofInstance(WorkspaceId ws, String instanceNo) {
        return jdbc.sql("SELECT * FROM proc_transition_log WHERE workspace_id = ? AND instance_no = ? ORDER BY at_ms, id")
                .params(ws.value(), instanceNo)
                .query((rs, i) -> new Entry(rs.getString("account_id"), rs.getString("entity_kind"), rs.getString("entity_no"),
                        rs.getString("instance_no"), rs.getString("subject_type"), rs.getString("subject_ref"), rs.getString("action"),
                        rs.getString("from_value"), rs.getString("to_value"), (Integer) rs.getObject("seq", Integer.class), rs.getString("actor"),
                        rs.getString("on_behalf_of"), rs.getString("reason"), ProcessJson.readMap(rs.getString("payload")), rs.getLong("at_ms")))
                .list();
    }
}
