package ai.neargo.opskit.platform.store.dao;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.platform.store.JsonColumns;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public final class MemberAttrDao {

    public record Row(String workspaceId, String attrKey, Map<String, String> name, Map<String, Map<String, String>> valueLabels) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Map<String, String>>> LABELS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public MemberAttrDao(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    public void upsert(AccountId account, WorkspaceId ws, Row r, String updatedBy, long now) {
        String sql = dialect.upsert("plat_member_attr", List.of("account_id", "workspace_id", "attr_key"),
                List.of("account_id", "workspace_id", "attr_key", "name", "value_labels", "updated_by", "updated_at_ms"),
                List.of("name", "value_labels", "updated_by", "updated_at_ms"), Set.of("name", "value_labels"));
        jdbc.sql(sql).params(account.value(), ws.value(), r.attrKey(), JsonColumns.write(r.name()), JsonColumns.write(r.valueLabels()),
                updatedBy, now).update();
    }

    /** 该空间可见的全部属性定义；同键时空间覆盖模板。 */
    public Map<String, Row> visibleIn(AccountId account, WorkspaceId ws) {
        Map<String, Row> out = new HashMap<>();
        jdbc.sql("SELECT * FROM plat_member_attr WHERE account_id = ? AND workspace_id IN ('', ?) ORDER BY CASE WHEN workspace_id = '' THEN 0 ELSE 1 END")
                .params(account.value(), ws.value())
                .query((rs, i) -> new Row(rs.getString("workspace_id"), rs.getString("attr_key"), JsonColumns.readText(rs.getString("name")),
                        JSON.readValue(rs.getString("value_labels"), LABELS)))
                .list().forEach(r -> out.put(r.attrKey(), r));
        return out;
    }
}
