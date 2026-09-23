package ai.neargo.sharehub.platform.iam;

import ai.neargo.common.data.scope.DataScopeResolver;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.common.security.rbac.AuthSubject;
import ai.neargo.common.security.rbac.PermissionResolver;
import ai.neargo.sharehub.auth.DevMode;
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
     * 口令闸：要求用户名 = admin + 密码匹配。
     *
     * <p><b>2026-09-23 安全止血</b>（TDD-auth-security-hotfix）：此前为空 = 关闸，
     * 任意 username + 前端自选 role 直接发 token。现在改为 <b>fail-closed</b> ——
     * 未配置口令时一律拒绝登录，除非 {@link DevMode} 显式开启（仅本机联调）。
     */
    @Value("${sharehub.admin.password:}")
    private String adminPassword;

    /** admin 账号绑定的角色 —— 由账号确定，登录页不再让用户挑（避免选错角色进后看到「无权限」）。 */
    @Value("${sharehub.admin.role:ADMIN}")
    private String adminRole;

    private final DevMode devMode;

    public AuthController(TokenStore tokenStore, PermissionResolver permissionResolver,
                          DataScopeResolver dataScopeResolver, MenuService menuService,
                          PermVersion permVersion, DevMode devMode) {
        this.devMode = devMode;
        this.tokenStore = tokenStore;
        this.permissionResolver = permissionResolver;
        this.dataScopeResolver = dataScopeResolver;
        this.menuService = menuService;
        this.permVersion = permVersion;
    }

    /**
     * 登录入参。{@code role} **保留但不再受理**（2026-09-23 起角色只由账号决定）——
     * 字段留着是为了两个前端不必同步改就能继续登录，可在前端改完后删除。
     */
    public record LoginReq(String username, String password, String role, String agentNo) {
    }

    public record LoginResp(String token, String username, String role, String agentNo, List<String> perms) {
    }

    @PostMapping("/login")
    public LoginResp login(@RequestBody LoginReq in) {
        String username = (in.username() == null || in.username().isBlank()) ? "user" : in.username();
        String role;
        boolean gateConfigured = adminPassword != null && !adminPassword.isBlank();
        if (gateConfigured) {
            // 正常路径：账号 + 口令都对才放行；**角色只由账号决定**，前端传的 role 一概不认
            // （此前闸门关闭时前端可自选 ADMIN —— v4/06 §〇 第 1 条）。
            if (!"admin".equals(username) || !adminPassword.equals(in.password())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
            }
            role = adminRole;
        } else if (devMode.isEnabled()) {
            // 本机联调 / 集成测试：允许免密，且**此分支**仍按前端传入的角色发 token ——
            // 真实账号与凭据库要等 A3（v4/06），在那之前多角色回归测试只能这样跑。
            // 生产走不到这里：dev-mode 默认关，且配了口令时上面的分支优先。
            role = (in.role() == null || in.role().isBlank()) ? "VIEWER" : in.role();
        } else {
            // fail-closed：没配口令又不是 dev-mode —— 拒绝，而不是放行任何人进来
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "登录未开通：未配置 sharehub.admin.password");
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
