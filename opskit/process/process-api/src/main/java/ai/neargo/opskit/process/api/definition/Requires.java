package ai.neargo.opskit.process.api.definition;

import java.util.Set;

/** 走这条迁移时必须提供的字段与是否必须写评论（如「驳回必须写原因」）。 */
public record Requires(Set<String> fields, boolean comment) {
    public static final Requires NONE = new Requires(Set.of(), false);

    public Requires {
        fields = fields == null ? Set.of() : Set.copyOf(fields);
    }
}
