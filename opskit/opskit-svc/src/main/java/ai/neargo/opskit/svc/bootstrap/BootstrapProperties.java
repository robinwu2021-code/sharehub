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
 * @param apps           应用代号 → 部署方生成的凭证（okk_…）与签名密钥（oks_…），经环境变量注入；按代号幂等，改了即轮换
 * @param webhooks       订阅：哪个应用、回调地址、事件模式；以声明为准整体替换（不在声明里的停用）
 * @param operators      运维代号 → 运维凭证（经环境变量注入）
 * @param definitionsDir 部署方的定义目录（可空）：其下 option-sets/ · processes/ · sla-policies/ · ticket-types/ 的 *.json
 *                       按代号幂等发布 —— 内容不变不出新版本。JSON 形状与定义的规范化存储一致，见 deploy/opskit/definitions.example/
 */
@ConfigurationProperties("opskit.bootstrap")
public record BootstrapProperties(boolean enabled, String accountName, Workspace workspace, List<Idp> idps, Map<String, Role> roles,
        String definitionsDir, Map<String, App> apps, List<Webhook> webhooks,
        Map<String, Operator> operators) {

    public BootstrapProperties {
        idps = idps == null ? List.of() : List.copyOf(idps);
        roles = roles == null ? Map.of() : Map.copyOf(roles);
        apps = apps == null ? Map.of() : Map.copyOf(apps);
        webhooks = webhooks == null ? List.of() : List.copyOf(webhooks);
        operators = operators == null ? Map.of() : Map.copyOf(operators);
    }

    /** 运维凭证 {@code opk_…}：账户级运维面（开空间 · 签发应用凭证 · 审计 · 跨空间投递）。只存哈希、不进库，改值重启即换 */
    public record Operator(String credential) {
    }

    public record App(String credential, String signingSecret) {
    }

    /** @param app apps 段里的代号 */
    public record Webhook(String app, String url, List<String> events) {
        public Webhook {
            events = events == null ? List.of() : List.copyOf(events);
        }
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
