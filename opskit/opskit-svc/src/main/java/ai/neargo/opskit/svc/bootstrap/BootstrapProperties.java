package ai.neargo.opskit.svc.bootstrap;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 私有化 / 单宿主部署的启动引导（21/01 NFR-11）：账户 · 空间 · IdP · 角色 → 产品权限。
 * 这些是部署方的数据（宿主的令牌前缀、角色代号），放在外部配置文件里，不进产品默认配置。
 *
 * @param idps  至少一个；账户按第一个 IdP 的签发方识别（已登记 = 账户已建）
 * @param roles 角色代号 → 名称与产品权限；令牌 roles 声明里的代号据此生效
 */
@ConfigurationProperties("opskit.bootstrap")
public record BootstrapProperties(boolean enabled, String accountName, Workspace workspace, List<Idp> idps, Map<String, Role> roles) {

    public BootstrapProperties {
        idps = idps == null ? List.of() : List.copyOf(idps);
        roles = roles == null ? Map.of() : Map.copyOf(roles);
    }

    public record Workspace(String ref, String name, String zone, String locale) {
    }

    /**
     * @param kind   JWT / INTROSPECT
     * @param claims 声明映射：member · displayName · workspace · groups · roles · perms · scopes
     */
    public record Idp(String issuer, String kind, String jwksUrl, String audience, String introspectUrl, String clientId, String clientSecret,
            String tokenPrefix, Map<String, String> claims, Boolean jit) {
    }

    public record Role(Map<String, String> name, List<String> permissions) {
        public Role {
            name = name == null ? Map.of() : Map.copyOf(name);
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
        }
    }
}
