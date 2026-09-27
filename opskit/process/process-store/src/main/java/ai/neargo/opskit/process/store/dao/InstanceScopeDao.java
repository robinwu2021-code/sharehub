package ai.neargo.opskit.process.store.dao;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 范围标签（proc_instance_scope）：多值、维度不限，可见性按「标签相交」判定。 */
public final class InstanceScopeDao {

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public InstanceScopeDao(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    public void replace(WorkspaceId ws, String instanceNo, Collection<String> tags) {
        jdbc.sql("DELETE FROM proc_instance_scope WHERE workspace_id = ? AND instance_no = ?").params(ws.value(), instanceNo).update();
        String sql = dialect.upsert("proc_instance_scope", List.of("workspace_id", "instance_no", "scope_tag"),
                List.of("workspace_id", "instance_no", "scope_tag"), List.of());
        for (String t : new TreeSet<>(tags)) {
            jdbc.sql(sql).params(ws.value(), instanceNo, t).update();
        }
    }

    public Set<String> of(WorkspaceId ws, String instanceNo) {
        return new TreeSet<>(jdbc.sql("SELECT scope_tag FROM proc_instance_scope WHERE workspace_id = ? AND instance_no = ?")
                .params(ws.value(), instanceNo).query(String.class).list());
    }
}
