package ai.neargo.opskit.platform.store.dao;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.platform.store.JsonColumns;
import ai.neargo.opskit.platform.store.row.CalendarRow;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class CalendarDao {

    private static final RowMapper<CalendarRow> MAPPER = (rs, i) -> new CalendarRow(
            rs.getString("account_id"), rs.getString("workspace_id"), rs.getString("calendar_code"), rs.getString("zone"),
            JsonColumns.readObjects(rs.getString("weekly")), JsonColumns.readSet(rs.getString("holidays")),
            rs.getString("updated_by"), rs.getLong("updated_at_ms"));

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public CalendarDao(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    public void upsert(AccountId account, WorkspaceId ws, CalendarRow r) {
        String sql = dialect.upsert("plat_calendar", List.of("account_id", "workspace_id", "calendar_code"),
                List.of("account_id", "workspace_id", "calendar_code", "zone", "weekly", "holidays", "updated_by", "updated_at_ms"),
                List.of("zone", "weekly", "holidays", "updated_by", "updated_at_ms"), Set.of("weekly", "holidays"));
        jdbc.sql(sql).params(account.value(), ws.value(), r.calendarCode(), r.zone(), JsonColumns.write(r.weekly()),
                JsonColumns.write(r.holidays()), r.updatedBy(), r.updatedAtMs()).update();
    }

    /** 空间自己的优先，没有再取账户模板。 */
    public Optional<CalendarRow> resolve(AccountId account, WorkspaceId ws, String code) {
        return jdbc.sql("SELECT * FROM plat_calendar WHERE account_id = ? AND workspace_id IN ('', ?) AND calendar_code = ? "
                        + "ORDER BY CASE WHEN workspace_id = '' THEN 1 ELSE 0 END")
                .params(account.value(), ws.value(), code).query(MAPPER).list().stream().findFirst();
    }
}
