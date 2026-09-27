package ai.neargo.opskit.process.store.dao;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 参与者（proc_participant）：角色 → 主体。 */
public final class ParticipantDao {

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public ParticipantDao(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    /** 已存在不报错（重复添加幂等）。 */
    public void add(WorkspaceId ws, String instanceNo, String role, String principal, long now) {
        jdbc.sql(dialect.upsert("proc_participant", List.of("workspace_id", "instance_no", "role", "principal"),
                        List.of("workspace_id", "instance_no", "role", "principal", "added_at_ms"), List.of()))
                .params(ws.value(), instanceNo, role, principal, now).update();
    }

    public void removeRole(WorkspaceId ws, String instanceNo, String role) {
        jdbc.sql("DELETE FROM proc_participant WHERE workspace_id = ? AND instance_no = ? AND role = ?").params(ws.value(), instanceNo, role).update();
    }

    public void remove(WorkspaceId ws, String instanceNo, String role, String principal) {
        jdbc.sql("DELETE FROM proc_participant WHERE workspace_id = ? AND instance_no = ? AND role = ? AND principal = ?")
                .params(ws.value(), instanceNo, role, principal).update();
    }

    public Map<String, Set<String>> byRole(WorkspaceId ws, String instanceNo) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        jdbc.sql("SELECT role, principal FROM proc_participant WHERE workspace_id = ? AND instance_no = ? ORDER BY role, principal")
                .params(ws.value(), instanceNo)
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getString(2))).list()
                .forEach(e -> out.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).add(e.getValue()));
        return out;
    }

    /** 这些主体（成员本人 + 所在组）在该实例上担任的角色。 */
    public Set<String> rolesOf(WorkspaceId ws, String instanceNo, Collection<String> principals) {
        if (principals.isEmpty()) {
            return Set.of();
        }
        return new TreeSet<>(jdbc.sql("SELECT DISTINCT role FROM proc_participant WHERE workspace_id = :ws AND instance_no = :no AND principal IN (:p)")
                .param("ws", ws.value()).param("no", instanceNo).param("p", principals).query(String.class).list());
    }
}
