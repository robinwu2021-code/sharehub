package ai.neargo.opskit.platform.api.directory;

import java.util.Set;

/**
 * 应用代成员调用时请求体里的 {@code onBehalfOf}（21/06 §2.1）。应用凭证是账户级的受信系统凭证，
 * 它给出的组 / 标签 / 宿主权限与令牌声明同等对待。
 *
 * @param groups 为 null = 不改目录里该成员「令牌来源」的组；给了（含空集）= 以此为准
 * @param perms  宿主权限码，只用于匹配候选里的 {@code perm:*}
 */
public record OnBehalfOf(String externalRef, String displayName, Set<String> groups, Set<String> scopes, Set<String> perms) {
    public OnBehalfOf {
        groups = groups == null ? null : Set.copyOf(groups);
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        perms = perms == null ? Set.of() : Set.copyOf(perms);
    }

    public static OnBehalfOf of(String externalRef) {
        return new OnBehalfOf(externalRef, null, null, Set.of(), Set.of());
    }
}
