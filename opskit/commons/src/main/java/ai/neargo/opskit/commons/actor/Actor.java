package ai.neargo.opskit.commons.actor;

import ai.neargo.opskit.commons.tenancy.TenantContext;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 一次调用的操作人。三类调用方（应用代成员 · 成员令牌 · 应用自身）最终都解析成它（02 §5.2）。
 *
 * @param member             操作的成员；应用自身调用时为空
 * @param app                经应用调用时的应用 ID；成员直接调用时为空
 * @param groups             所在组代号（令牌声明 ∪ 目录）
 * @param roles              角色代号
 * @param externalPerms      令牌带来的宿主权限（不可枚举，只能作候选条件）
 * @param productPermissions 产品权限目录中的码（由角色展开）
 * @param scopes             可见的范围标签
 */
public record Actor(
        TenantContext tenant,
        Optional<PrincipalRef> member,
        Optional<String> app,
        Set<String> groups,
        Set<String> roles,
        Set<String> externalPerms,
        Set<String> productPermissions,
        Set<String> scopes) {

    public Actor {
        Objects.requireNonNull(tenant, "tenant");
        member = Objects.requireNonNullElse(member, Optional.empty());
        app = Objects.requireNonNullElse(app, Optional.empty());
        if (member.isPresent() && member.get().kind() != PrincipalRef.Kind.MEMBER) {
            throw new IllegalArgumentException("actor member must be member:*, got " + member.get());
        }
        if (member.isEmpty() && app.isEmpty()) {
            throw new IllegalArgumentException("actor needs a member or an app");
        }
        groups = Set.copyOf(groups);
        roles = Set.copyOf(roles);
        externalPerms = Set.copyOf(externalPerms);
        productPermissions = Set.copyOf(productPermissions);
        scopes = Set.copyOf(scopes);
    }

    public boolean has(String productPermission) {
        return productPermissions.contains(productPermission);
    }

    /** 应用自身（不代任何成员）调用，如外部确认门的回复。 */
    public boolean isAppItself() {
        return member.isEmpty();
    }
}
