package ai.neargo.opskit.platform.api.federation;

/**
 * 令牌声明 → OpsKit 身份（值是**声明名**，不是声明值）。未配置的项视为「令牌不提供」—— 按 fail-closed 处理：
 * 没有角色声明就只有目录里绑定的角色，没有范围声明就只看得见自己参与的。
 *
 * @param member      成员外部编号所在的声明（必填，常为 {@code sub}）
 * @param workspace   伙伴租户号所在的声明；配了就要求与路径里的空间一致（防止拿 A 运营商的令牌访问 B）
 * @param groups      组代号（数组或空格分隔字符串）
 * @param roles       OpsKit 角色代号
 * @param perms       宿主权限码（用于匹配候选里的 perm:*）
 * @param scopes      范围标签
 */
public record ClaimMapping(String member, String displayName, String workspace, String groups, String roles, String perms, String scopes) {
    public ClaimMapping {
        if (member == null || member.isBlank()) {
            throw new IllegalArgumentException("claim mapping needs the member claim name");
        }
    }
}
