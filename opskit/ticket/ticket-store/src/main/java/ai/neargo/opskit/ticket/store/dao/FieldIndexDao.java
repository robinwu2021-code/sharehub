package ai.neargo.opskit.ticket.store.dao;

import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 可筛选字段索引（tkt_field_value）：一张工单的索引整体替换（多值字段一值一行）。 */
public final class FieldIndexDao {

    public record Value(String field, String vStr, BigDecimal vNum, Long vTime) {
    }

    private final JdbcClient jdbc;

    public FieldIndexDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 整体替换。先查出主键再按主键删：MySQL / MariaDB 可重复读下按二级索引范围删（哪怕一行都没有）会加间隙锁，
     * 与并发建单往相邻间隙里插索引行互相死锁（TicketDispatchTest 并发轮转实测）；按主键删只加记录锁。
     */
    public void replace(WorkspaceId ws, String ticketNo, String typeCode, List<Value> values) {
        List<Long> ids = jdbc.sql("SELECT id FROM tkt_field_value WHERE workspace_id = ? AND ticket_no = ?").params(ws.value(), ticketNo)
                .query(Long.class).list();
        if (!ids.isEmpty()) {
            jdbc.sql("DELETE FROM tkt_field_value WHERE id IN (:ids)").param("ids", ids).update();
        }
        for (Value v : values) {
            jdbc.sql("INSERT INTO tkt_field_value (workspace_id, ticket_no, type_code, field_code, v_str, v_num, v_time) VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(ws.value(), ticketNo, typeCode, v.field(), v.vStr(), v.vNum(), v.vTime()).update();
        }
    }

    public List<Value> of(WorkspaceId ws, String ticketNo) {
        return jdbc.sql("SELECT field_code, v_str, v_num, v_time FROM tkt_field_value WHERE workspace_id = ? AND ticket_no = ? ORDER BY field_code, id")
                .params(ws.value(), ticketNo)
                .query((rs, i) -> new Value(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getObject(4, Long.class))).list();
    }
}
