package ai.neargo.opskit.process.api.definition;

import java.util.List;
import java.util.Objects;

/**
 * @param values MEMBER：成员 ID；GROUP / ROLE / PERM：代号；FIELD：实例字段代号（字段值是成员 ID 或其列表）；PARTICIPANT：参与者角色
 */
public record ApproverRef(ApproverKind kind, List<String> values) {
    public ApproverRef {
        Objects.requireNonNull(kind, "kind");
        values = values == null ? List.of() : List.copyOf(values);
    }
}
