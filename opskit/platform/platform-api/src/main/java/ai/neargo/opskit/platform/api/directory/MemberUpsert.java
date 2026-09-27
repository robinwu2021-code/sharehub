package ai.neargo.opskit.platform.api.directory;

import java.util.Map;
import java.util.Set;

/**
 * @param externalRef 宿主里的用户编号（空间内唯一）
 * @param attrs       租户自定义属性，如 {@code {"partyType":"AGENT"}}；只允许标量与标量列表
 * @param scopes      目录维护的可见范围标签（与令牌声明取并集）
 */
public record MemberUpsert(String externalRef, String displayName, Map<String, Object> attrs, Set<String> scopes) {
    public MemberUpsert {
        attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    public static MemberUpsert of(String externalRef) {
        return new MemberUpsert(externalRef, null, Map.of(), Set.of());
    }
}
