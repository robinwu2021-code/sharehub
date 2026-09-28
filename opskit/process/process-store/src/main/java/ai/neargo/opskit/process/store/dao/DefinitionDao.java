package ai.neargo.opskit.process.store.dao;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 流程定义与 SLA 策略（proc_definition，按版本只增）及其启停（proc_definition_status）。 */
public final class DefinitionDao {

    /** workspaceId 空串 = 账户级模板。 */
    public record Row(long id, String accountId, String workspaceId, String kind, String code, String version, String name, String body,
            boolean latest, String channel, String note, String publishedBy, long publishedAtMs) {
    }

    private static final RowMapper<Row> MAPPER = (rs, i) -> new Row(rs.getLong("id"), rs.getString("account_id"), rs.getString("workspace_id"),
            rs.getString("kind"), rs.getString("code"), rs.getString("version"), rs.getString("name"), rs.getString("body"),
            rs.getInt("latest") == 1, rs.getString("channel"), rs.getString("note"), rs.getString("published_by"), rs.getLong("published_at_ms"));

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public DefinitionDao(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    /** 同版本已存在则什么也不做（内容哈希相同 = 同一份定义）；不报错。 */
    public void insertIfAbsent(AccountId account, WorkspaceId ws, String kind, String code, String version, String nameJson, String body,
            String channel, String note, String by, long now) {
        String sql = dialect.upsert("proc_definition", List.of("account_id", "workspace_id", "kind", "code", "version"),
                List.of("account_id", "workspace_id", "kind", "code", "version", "name", "body", "latest", "channel", "note", "published_by", "published_at_ms"),
                List.of(), Set.of("name", "body"));
        jdbc.sql(sql).params(account.value(), ws.value(), kind, code, version, nameJson, body, 0, channel, note, by, now).update();
    }

    /** 把指定版本设为当前版本，其余版本取消（同一事务里调用）。 */
    public void markLatest(AccountId account, WorkspaceId ws, String kind, String code, String version) {
        jdbc.sql("UPDATE proc_definition SET latest = CASE WHEN version = ? THEN 1 ELSE 0 END WHERE account_id = ? AND workspace_id = ? AND kind = ? AND code = ?")
                .params(version, account.value(), ws.value(), kind, code).update();
    }

    public Optional<Row> latest(AccountId account, WorkspaceId ws, String kind, String code) {
        return jdbc.sql("SELECT * FROM proc_definition WHERE account_id = ? AND workspace_id = ? AND kind = ? AND code = ? AND latest = 1")
                .params(account.value(), ws.value(), kind, code).query(MAPPER).optional();
    }

    public Optional<Row> byVersion(AccountId account, WorkspaceId ws, String kind, String code, String version) {
        return jdbc.sql("SELECT * FROM proc_definition WHERE account_id = ? AND workspace_id = ? AND kind = ? AND code = ? AND version = ?")
                .params(account.value(), ws.value(), kind, code, version).query(MAPPER).optional();
    }

    public Optional<Row> byId(AccountId account, long id) {
        return jdbc.sql("SELECT * FROM proc_definition WHERE account_id = ? AND id = ?").params(account.value(), id).query(MAPPER).optional();
    }

    public List<Row> versions(AccountId account, WorkspaceId ws, String kind, String code) {
        return jdbc.sql("SELECT * FROM proc_definition WHERE account_id = ? AND workspace_id = ? AND kind = ? AND code = ? ORDER BY published_at_ms DESC, id DESC")
                .params(account.value(), ws.value(), kind, code).query(MAPPER).list();
    }

    /** 空间当前可用的全部定义：空间自己的当前版本 + 没被覆盖的模板当前版本。 */
    public List<Row> latestVisible(AccountId account, WorkspaceId ws, String kind) {
        return jdbc.sql("SELECT * FROM proc_definition WHERE account_id = ? AND workspace_id IN ('', ?) AND kind = ? AND latest = 1 "
                        + "ORDER BY code, CASE WHEN workspace_id = '' THEN 1 ELSE 0 END")
                .params(account.value(), ws.value(), kind).query(MAPPER).list();
    }

    public void setEnabled(AccountId account, WorkspaceId ws, String kind, String code, boolean enabled, String by, long now) {
        jdbc.sql(dialect.upsert("proc_definition_status", List.of("account_id", "workspace_id", "kind", "code"),
                        List.of("account_id", "workspace_id", "kind", "code", "enabled", "updated_by", "updated_at_ms"),
                        List.of("enabled", "updated_by", "updated_at_ms")))
                .params(account.value(), ws.value(), kind, code, enabled ? 1 : 0, by, now).update();
    }

    /** 没有启停记录 = 启用。空间级的记录优先于模板级。 */
    public boolean enabled(AccountId account, WorkspaceId ws, String kind, String code) {
        return jdbc.sql("SELECT enabled FROM proc_definition_status WHERE account_id = ? AND workspace_id IN ('', ?) AND kind = ? AND code = ? "
                        + "ORDER BY CASE WHEN workspace_id = '' THEN 1 ELSE 0 END")
                .params(account.value(), ws.value(), kind, code).query(Integer.class).list().stream().findFirst().map(v -> v == 1).orElse(true);
    }
}
