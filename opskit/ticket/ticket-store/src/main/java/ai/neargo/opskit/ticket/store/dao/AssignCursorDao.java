package ai.neargo.opskit.ticket.store.dao;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 轮转派单游标（tkt_assign_cursor）：每个（类型, 组）一行。先「不存在才插」再加锁读，并发派单在这一行上排队，不会重复分给同一人。
 */
public final class AssignCursorDao {

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public AssignCursorDao(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    /** 加锁读上一次分到的成员 ID（没有为 null）；须在事务里调用，随后用 {@link #advance} 写回。 */
    public String lockLast(WorkspaceId ws, String typeCode, String groupCode) {
        jdbc.sql(dialect.upsert("tkt_assign_cursor", List.of("workspace_id", "type_code", "group_code"),
                        List.of("workspace_id", "type_code", "group_code", "version"), List.of()))
                .params(ws.value(), typeCode, groupCode, 0).update();
        return jdbc.sql("SELECT last_member_id FROM tkt_assign_cursor WHERE workspace_id = ? AND type_code = ? AND group_code = ? FOR UPDATE")
                .params(ws.value(), typeCode, groupCode).query((rs, i) -> java.util.Optional.ofNullable(rs.getString(1))).single().orElse(null);
    }

    public void advance(WorkspaceId ws, String typeCode, String groupCode, String memberId) {
        jdbc.sql("UPDATE tkt_assign_cursor SET last_member_id = ?, version = version + 1 WHERE workspace_id = ? AND type_code = ? AND group_code = ?")
                .params(memberId, ws.value(), typeCode, groupCode).update();
    }
}
