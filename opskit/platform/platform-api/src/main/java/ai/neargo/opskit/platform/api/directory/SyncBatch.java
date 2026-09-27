package ai.neargo.opskit.platform.api.directory;

import java.util.List;
import java.util.Map;

/**
 * 目录同步（21/06 §三 {@code POST /workspaces/{ws}/directory/sync}）。
 *
 * <p>MERGE：只增改，不删。REPLACE：以本批为准 —— 本批里出现的组，其 SYNC 来源的组员替换为本批所给；
 * 本批出现的成员，其 SYNC 来源的角色绑定替换为本批所给。不在本批里的组 / 成员不动，
 * CLAIM 与 CONSOLE 来源的数据永远不动。
 *
 * @param memberships 组代号 → 成员外部编号列表
 */
public record SyncBatch(Mode mode, List<MemberUpsert> members, List<GroupUpsert> groups,
        Map<String, List<String>> memberships, List<RoleBinding> roleBindings) {

    public enum Mode { MERGE, REPLACE }

    public SyncBatch {
        mode = mode == null ? Mode.MERGE : mode;
        members = members == null ? List.of() : List.copyOf(members);
        groups = groups == null ? List.of() : List.copyOf(groups);
        memberships = memberships == null ? Map.of() : Map.copyOf(memberships);
        roleBindings = roleBindings == null ? List.of() : List.copyOf(roleBindings);
    }
}
