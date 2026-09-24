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
import ai.neargo.sharehub.platform.iam.port.AgentIdentityPort;
import ai.neargo.sharehub.platform.iam.port.AgentIdentityPort.OperatorMembership;
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

    /** 代理端实名登录（④⑤）。入驻建出来的号靠它才登得进来 —— 见 AgentIdentityPort 类注释。 */
    private final AgentIdentityPort agentLogin;

    public AuthController(TokenStore tokenStore, PermissionResolver permissionResolver,
                          DataScopeResolver dataScopeResolver, MenuService menuService,
                          PermVersion permVersion, DevMode devMode, AgentIdentityPort agentLogin) {
        this.agentLogin = agentLogin;
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
    public record LoginReq(String username, String password, String role, String agentNo,
                          // —— 代理端实名登录（④）：手机号 + 验证码。两者都给才走代理分支 ——
                          String phone, String otp) {
    }

    /**
     * 登录出参。{@code principalNo} 与 {@code operators} 是 ADR-030 的兑现：
     * 一次登录就把「我是谁、我属于哪几个主体」一起给出去，前端不必再多一趟。
     * 运营端（STAFF）登录时这两个字段为 null / 空表。
     */
    public record LoginResp(String token, String username, String role, String agentNo, List<String> perms,
                            String principalNo, List<OperatorMembership> operators) {
    }

    @PostMapping("/login")
    public LoginResp login(@RequestBody LoginReq in) {
        /*
         * —— 代理端实名登录（必要功能清单 ④）——
         *
         * 放在最前面：它是**唯一一条会去读 agt_principal / agt_account 的路径**。
         * 在这之前，入驻审核通过后建出来的账号没有任何代码会在登录时读它 ——
         * ①–③ 做完了却接不上电。下面的 admin 口令闸与 dev-mode 分支保持原样，
         * 这里只是多开一条道，不改既有运营端登录的任何语义。
         */
        if (in != null && in.otp() != null && !in.otp().isBlank()) {
            return agentLogin(in);
        }
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
        return new LoginResp(token, username, role, agentNo, perms, null, List.of());
    }

    /**
     * 发送代理端登录验证码（匿名）。
     *
     * <p><b>查无此号也返回 ok</b> —— 见 {@code AgentIdentityPort.sendLoginOtp}：
     * 一旦「存在」与「不存在」返回不同，这个接口就成了代理商手机号枚举器。
     * 验证码只在 dev-mode 回显，生产走短信通道。
     */
    @PostMapping("/otp")
    public Map<String, Object> loginOtp(@RequestBody Map<String, String> body) {
        String phone = body == null ? null : body.get("phone");
        if (phone == null || phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写手机号");
        }
        String code;
        try {
            code = agentLogin.sendLoginOtp(phone);
        } catch (IllegalArgumentException e) {           // 手机号格式不合法
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {              // 重发过频（OtpGate 节流）
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        }
        return devMode.isEnabled() && code != null
                ? Map.of("ok", true, "code", code)
                : Map.of("ok", true);
    }

    /**
     * 我的运营主体列表（必要功能清单 ⑤ / ADR-030 §2.2）。
     *
     * <p>ADR-030 定了「一个人对多行 {@code agt_account}」，库和前端 store 都备好了，
     * 而此前<b>没有任何端点</b>把这份名单告诉前端 —— 一人服务两家运营商时登录后无从选择。
     */
    @GetMapping("/operators")
    public List<OperatorMembership> operators() {
        LoginUser u = SecurityUtils.requireUser();
        // 代理会话的 userNo 存的就是 principalNo（见 agentLogin()）；运营端会话没有主体概念
        return u.realm() == Realm.AGENT ? agentLogin.memberships(u.userNo()) : List.of();
    }

    /**
     * 切换当前运营主体：**换发 token**，而不是改会话里的一个字段。
     *
     * <p>因为 {@code agentNo} 是数据范围的锚点（{@code PermissionService.resolveDataScope}
     * 按它做 AGENT 硬过滤）。原地改字段的话，旧 token 仍在别处使用时会拿着旧范围继续跑；
     * 换发之后老 token 立刻吊销，**没有两个范围并存的窗口**。
     */
    @PostMapping("/operators/{agentNo}/switch")
    public LoginResp switchOperator(@PathVariable String agentNo,
                                    @RequestHeader(value = "Authorization", required = false) String auth) {
        LoginUser u = SecurityUtils.requireUser();
        if (u.realm() != Realm.AGENT) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有代理端会话可以切换运营主体");
        }
        OperatorMembership m;
        try {
            m = agentLogin.requireMembership(u.userNo(), agentNo);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
        LoginResp resp = issueAgentToken(u.userNo(), m);
        // 先发新的再吊销旧的：反过来的话中间那一瞬客户端两个 token 都不可用
        if (auth != null && auth.startsWith("Bearer ")) {
            tokenStore.revoke(auth.substring(7).trim());
        }
        return resp;
    }

    /** 当前登录人可见菜单树（动态，随权限增减）。 */
    /**
     * 菜单树（真源，2026-09-24 起前端消费它，不再用本地 nav.ts）。
     *
     * <p>**不按权限剪枝** —— 可见性由前端那一套规则决定，理由见 {@link MenuService}。
     * 仍需登录：菜单名与路径不该对匿名者下发。
     */
    @GetMapping("/menus")
    public List<MenuNode> menus() {
        SecurityUtils.requireUser();
        return menuService.tree();
    }

    /** 当前登录人权限码集合（前端 can() 用）。 */
    @GetMapping("/permissions")
    public List<String> permissions() {
        return SecurityUtils.requireUser().perms();
    }

    /**
     * 当前会话的身份与**当场重算的权限码**。ops-web 进应用与标签页重新可见时会拉它刷新 perms。
     *
     * <p><b>不用 {@code Map.of}</b>：它遇到任何一个 null 值就抛 NPE。
     * {@code CurrentUser.system()} 的 {@code agentNo} 就是 null，
     * 而本端点现在是前端的常规调用 —— 一旦 500，前端的 perms-sync 会把异常吞掉
     * （那是对的：一次刷新失败不该把人弹回登录页），于是**权限从此不再刷新，且毫无症状**。
     * 用 HashMap 收 null，把「少一个字段」留在数据里，而不是变成一次 500。
     */
    @GetMapping("/me")
    public Map<String, Object> me() {
        return SecurityUtils.currentUser()
                .<Map<String, Object>>map(u -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("authenticated", true);
                    m.put("username", u.username());
                    m.put("role", u.role());
                    m.put("agentNo", u.agentNo() == null ? "" : u.agentNo());
                    m.put("perms", u.perms() == null ? List.of() : u.perms());
                    return m;
                })
                .orElse(Map.of("authenticated", false));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth != null && auth.startsWith("Bearer ")) {
            tokenStore.revoke(auth.substring(7).trim());
        }
        return Map.of("ok", true);
    }

    /** 代理端登录分支：验码 → 定主体 → 发 token。 */
    private LoginResp agentLogin(LoginReq in) {
        String principalNo;
        try {
            principalNo = agentLogin.verifyLoginOtp(in.phone(), in.otp());
        } catch (IllegalArgumentException e) {
            // 文案由 service 统一给（不区分「号不存在」与「码不对」）
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
        OperatorMembership m;
        try {
            m = (in.agentNo() == null || in.agentNo().isBlank())
                    ? agentLogin.primaryOf(principalNo)
                    : agentLogin.requireMembership(principalNo, in.agentNo());
        } catch (IllegalStateException e) {        // 名下一个主体都没有
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {     // 指定了不属于自己的主体
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
        return issueAgentToken(principalNo, m);
    }

    /**
     * 按「自然人 × 主体」发一张代理 token。
     *
     * <p>{@code userNo} 存 {@code principalNo} 而不是 {@code accountNo}：
     * 切换主体时 account 会变、人不会变，{@code /operators} 要按人查。
     * {@code LoginUser} 在 {@code common} 里被 C 端等共用，<b>不为此加字段</b> ——
     * 加一个字段要改所有构造点，而 {@code userNo} 本来就是「这个会话是谁」。
     */
    private LoginResp issueAgentToken(String principalNo, OperatorMembership m) {
        String role = "AGENT";
        String display = (m.displayName() == null || m.displayName().isBlank())
                ? m.agentName() : m.displayName();
        List<String> perms = List.copyOf(permissionResolver.resolvePermissions(
                rolesOnly(principalNo, role)));
        AuthSubject subject = new AuthSubject(Realm.AGENT.name(), principalNo, List.of(role), "MAIN",
                Map.of("agentNo", m.agentNo()));
        DataScopeSpec scope = dataScopeResolver.resolveDataScope(subject);
        LoginUser user = new LoginUser(Realm.AGENT, principalNo, display, role, perms, "MAIN",
                m.agentNo(), scope);
        String token = tokenStore.issue(new TokenStore.SessionData(user, List.of(role), permVersion.get()));
        return new LoginResp(token, display, role, m.agentNo(), perms,
                principalNo, agentLogin.memberships(principalNo));
    }

    /** 仅含角色的主体（resolvePermissions 只看 roles，realm 用占位）。 */
    private static AuthSubject rolesOnly(String username, String role) {
        return new AuthSubject(Realm.STAFF.name(), username, List.of(role), "MAIN", Map.of());
    }
}
