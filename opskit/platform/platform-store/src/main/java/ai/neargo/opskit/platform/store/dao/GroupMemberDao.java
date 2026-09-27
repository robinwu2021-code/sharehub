package ai.neargo.opskit.platform.store.dao;

import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 组成员关系按来源分管（DirectorySource）：每个方法都带来源，只动自己那一份。 */
public final class GroupMemberDao {

    private final JdbcClient jdbc;
    private final ai.neargo.opskit.commons.jdbc.Dialect dialect;

    public GroupMemberDao(JdbcClient jdbc, ai.neargo.opskit.commons.jdbc.Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    /** 成员所在的全部组（各来源合并）。 */
    public Set<String> groupsOf(WorkspaceId ws, String memberId) {
        return new TreeSet<>(jdbc.sql("SELECT DISTINCT group_code FROM plat_group_member WHERE workspace_id = ? AND member_id = ?")
                .params(ws.value(), memberId).query(String.class).list());
    }

    public Set<String> groupsOfBySource(WorkspaceId ws, String memberId, String source) {
        return new TreeSet<>(jdbc.sql("SELECT group_code FROM plat_group_member WHERE workspace_id = ? AND member_id = ? AND source = ?")
                .params(ws.value(), memberId, source).query(String.class).list());
    }

    /** 组的全部成员（各来源合并）—— 会签快照、轮转派单用。 */
    public List<String> membersOf(WorkspaceId ws, String groupCode) {
        return jdbc.sql("SELECT DISTINCT member_id FROM plat_group_member WHERE workspace_id = ? AND group_code = ? ORDER BY member_id")
                .params(ws.value(), groupCode).query(String.class).list();
    }

    /** 唯一键是 (workspace, group, member)，与来源无关：已由别的来源建立的关系不重复插、也不改来源；并发重复不报错。 */
    public void add(WorkspaceId ws, String groupCode, String memberId, String source, long now) {
        jdbc.sql(dialect.upsert("plat_group_member", List.of("workspace_id", "group_code", "member_id"),
                        List.of("workspace_id", "group_code", "member_id", "source", "updated_at_ms"), List.of()))
                .params(ws.value(), groupCode, memberId, source, now).update();
    }

    public void removeBySource(WorkspaceId ws, String groupCode, String memberId, String source) {
        jdbc.sql("DELETE FROM plat_group_member WHERE workspace_id = ? AND group_code = ? AND member_id = ? AND source = ?")
                .params(ws.value(), groupCode, memberId, source).update();
    }

    /** REPLACE 同步：该组 SYNC 来源的成员里，不在 keep 集合的删掉。 */
    public void retainBySource(WorkspaceId ws, String groupCode, Collection<String> keepMemberIds, String source) {
        List<String> current = jdbc.sql("SELECT member_id FROM plat_group_member WHERE workspace_id = ? AND group_code = ? AND source = ?")
                .params(ws.value(), groupCode, source).query(String.class).list();
        for (String m : current) {
            if (!keepMemberIds.contains(m)) {
                removeBySource(ws, groupCode, m, source);
            }
        }
    }
}
