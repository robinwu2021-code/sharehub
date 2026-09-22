package ai.neargo.sharehub.platform.iam;

import ai.neargo.common.data.scope.DataScopeResolver;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.common.security.rbac.AuthSubject;
import ai.neargo.common.security.rbac.PermissionResolver;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.PermVersion;
import ai.neargo.sharehub.auth.Realm;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.auth.TokenStore;
import ai.neargo.sharehub.platform.iam.MenuService.MenuNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 运营端登录/身份端点（属 IAM 模块）。MVP：按 {username, role} 发放 token（演示，无密码校验）——
 * 关键安全属性：登录后**后端据 token 反查的角色/权限鉴权**，不认客户端 X-Roles。
 * <p>权限/数据范围**由库装配**（{@link PermissionService} 读 iam_role_perm/iam_data_scope），
 * 会话记权限版本戳（口径 B）；菜单/权限码经 {@code /menus}、{@code /permissions} 动态下发。
 * 生产换 auth-core：pb_auth 凭据 + realm；员工→角色经 iam_employee_role 解析。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final TokenStore tokenStore;
    private final PermissionResolver permissionResolver;   // SPI（由 PermissionService 实现）
    private final DataScopeResolver dataScopeResolver;      // SPI（同上）
    private final MenuService menuService;
    private final PermVersion permVersion;

    /**
     * 生产口令闸：配置了 {@code sharehub.admin.password} 就要求用户名 = admin + 密码匹配。
     * 空字符串 = 关（保留原 MVP 演示行为：任意 username + role 直接发 token）。
     */
    @Value("${sharehub.admin.password:}")
    private String adminPassword;

    public AuthController(TokenStore tokenStore, PermissionResolver permissionResolver,
                          DataScopeResolver dataScopeResolver, MenuService menuService, PermVersion permVersion) {
        this.tokenStore = tokenStore;
        this.permissionResolver = permissionResolver;
        this.dataScopeResolver = dataScopeResolver;
        this.menuService = menuService;
        this.permVersion = permVersion;
    }

    public record LoginReq(String username, String password, String role, String agentNo) {
    }

    public record LoginResp(String token, String username, String role, String agentNo, List<String> perms) {
    }

    @PostMapping("/login")
    public LoginResp login(@RequestBody LoginReq in) {
        String username = (in.username() == null || in.username().isBlank()) ? "user" : in.username();
        String role = (in.role() == null || in.role().isBlank()) ? "VIEWER" : in.role();
        // 口令闸：sharehub.admin.password 非空则要求 username=admin + 密码匹配。
        // 空 = 关（保留 MVP 无密码演示；上生产必须配非空值 —— 见 deploy/tencent/README.md §5）。
        if (adminPassword != null && !adminPassword.isBlank()) {
            if (!"admin".equals(username) || !adminPassword.equals(in.password())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
            }
        }
        // 经 SPI 解析权限（resolvePermissions 只看 roles，realm 用占位）；未知角色 → 兜底 VIEWER
        List<String> perms = List.copyOf(permissionResolver.resolvePermissions(rolesOnly(username, role)));
        if (perms.isEmpty() && !"ADMIN".equals(role)) {
            role = "VIEWER";
            perms = List.copyOf(permissionResolver.resolvePermissions(rolesOnly(username, role)));
        }
        boolean isAgent = "AGENT".equals(role);
        String agentNo = isAgent ? (in.agentNo() == null || in.agentNo().isBlank() ? "AG001" : in.agentNo()) : "";
        Realm realm = isAgent ? Realm.AGENT : Realm.STAFF;
        List<String> roleNos = List.of(role);
        AuthSubject subject = new AuthSubject(realm.name(), username, roleNos, "MAIN", Map.of("agentNo", agentNo));
        DataScopeSpec scope = dataScopeResolver.resolveDataScope(subject);
        LoginUser user = new LoginUser(realm, username, username, role, perms, "MAIN", agentNo, scope);
        String token = tokenStore.issue(new TokenStore.SessionData(user, roleNos, permVersion.get()));
        return new LoginResp(token, username, role, agentNo, perms);
    }

    /** 当前登录人可见菜单树（动态，随权限增减）。 */
    @GetMapping("/menus")
    public List<MenuNode> menus() {
        return menuService.visibleFor(SecurityUtils.requireUser());
    }

    /** 当前登录人权限码集合（前端 can() 用）。 */
    @GetMapping("/permissions")
    public List<String> permissions() {
        return SecurityUtils.requireUser().perms();
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        return SecurityUtils.currentUser()
                .<Map<String, Object>>map(u -> Map.of(
                        "authenticated", true, "username", u.username(), "role", u.role(),
                        "agentNo", u.agentNo(), "perms", u.perms()))
                .orElse(Map.of("authenticated", false));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth != null && auth.startsWith("Bearer ")) {
            tokenStore.revoke(auth.substring(7).trim());
        }
        return Map.of("ok", true);
    }

    /** 仅含角色的主体（resolvePermissions 只看 roles，realm 用占位）。 */
    private static AuthSubject rolesOnly(String username, String role) {
        return new AuthSubject(Realm.STAFF.name(), username, List.of(role), "MAIN", Map.of());
    }
}
