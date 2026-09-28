package ai.neargo.sharehub.portal.platform;

import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.sharehub.api.platform.port.EmployeeDirectoryPort;
import ai.neargo.sharehub.api.platform.port.PermCatalogPort;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.StaffTokenAuthFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 宿主令牌内省端点（OpsKit 21/06 §八，形状同 RFC 7662）：OpsKit 拿到运营台的 {@code stk_…} / {@code atk_…} 令牌，
 * 按登记的前缀路由到这里问「这是谁、有哪些权限与范围」。运营台登录一次，流程与工单平台就认得这个人。
 *
 * <h3>三条约定</h3>
 * <ul>
 *   <li><b>只对 OpsKit 开放</b>：调用方带 {@code Authorization: Basic base64(clientId:clientSecret)}，与
 *       {@code SHAREHUB_OPSKIT_INTROSPECT_CLIENT_ID / _SECRET} 比对。两项没配 → 一律 401（fail-closed，不退化成放行）。</li>
 *   <li><b>令牌无效一律 {@code 200 {"active":false}}</b>：过期、登出、伪造、停用、C 端令牌都这样，不区分 —— 区分就是给探测者递线索。
 *       只有「调用方凭据不对」才回 401。</li>
 *   <li><b>与过滤器同一条校验</b>：{@link StaffTokenAuthFilter#resolve}（前缀对账、权限按版本重算、主体失效即无效）。
 *       所以员工权限一改，OpsKit 最多在其缓存期（≤5 分钟）后看到新权限。</li>
 * </ul>
 *
 * <p>应答<b>不包</b> {@code Result{code,message,data}}（RFC 7662 是裸 JSON）：直接写 response，绕开 ApiResponseWrapper。
 * 应答里不放手机号等个人信息 —— 只有编号、显示名、权限、范围。
 */
@RestController
@RequestMapping("/internal/opskit")
public class OpsKitIntrospectController {

    private static final Logger log = LoggerFactory.getLogger(OpsKitIntrospectController.class);

    /** 与项目其他处一致用静态实例：Boot 4 默认不注册 Jackson 2 的 ObjectMapper Bean */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StaffTokenAuthFilter tokens;
    private final String clientId;
    private final String clientSecret;
    /** 可空：只有展开通配码用得到；测试里直接 new 本类时不给 */
    private final PermCatalogPort catalog;
    /** 可空：员工显示名取姓名（登录名即工号，不给姓名的话 OpsKit 里派单列表是一串工号） */
    private final EmployeeDirectoryPort employees;
    private volatile Set<String> catalogCache = Set.of();
    private volatile long catalogAt;
    private static final long CATALOG_TTL_MS = 60_000;

    @org.springframework.beans.factory.annotation.Autowired
    public OpsKitIntrospectController(StaffTokenAuthFilter tokens,
                                      @Value("${sharehub.opskit.introspect.client-id:}") String clientId,
                                      @Value("${sharehub.opskit.introspect.client-secret:}") String clientSecret,
                                      PermCatalogPort catalog,
                                      EmployeeDirectoryPort employees) {
        this.tokens = tokens;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.catalog = catalog;
        this.employees = employees;
    }

    /** 测试用：不展开通配码 */
    public OpsKitIntrospectController(StaffTokenAuthFilter tokens, String clientId, String clientSecret) {
        this(tokens, clientId, clientSecret, null, null);
    }

    @PostMapping(value = "/introspect", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public void introspect(@RequestParam(value = "token", required = false) String token,
                           HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!callerAllowed(req.getHeader("Authorization"))) {
            // 谁该做什么：核对 OpsKit 的 IdP 登记（clientId / secret）与本服务的 SHAREHUB_OPSKIT_INTROSPECT_* 是否一致
            log.warn("OpsKit 内省被拒：调用方凭据缺失或不符 remote={} configured={}（核对 OpsKit IdP 登记与 SHAREHUB_OPSKIT_INTROSPECT_*）",
                    req.getRemoteAddr(), !clientId.isBlank() && !clientSecret.isBlank());
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.setHeader("WWW-Authenticate", "Basic realm=\"opskit-introspect\"");
            write(resp, Map.of("error", "invalid_client"));
            return;
        }
        LoginUser u = token == null || token.isBlank() ? null : tokens.resolve(token.trim()).orElse(null);
        write(resp, u == null ? Map.of("active", false) : claims(u, expand(u.perms()), displayName(u)));
    }

    /** 声明名与 21/06 §八 的示例一致；OpsKit 侧按 IdP 登记里的声明映射对上 */
    /**
     * 通配码展开成目录里的具体码（原通配码也保留）。OpsKit 按候选键<b>精确</b>匹配审批人 ——
     * 只发 {@code finance:*} 的话，「持 finance:withdrawal:audit 的人」这一步永远没有候选人（M5 实测前发现）。
     * 规则与 ShareHub 判权一致的直觉：{@code *} 单独 = 全部；末段 {@code *} = 该前缀下全部；中段 {@code *} = 恰好一段。
     */
    List<String> expand(List<String> perms) {
        if (perms == null || perms.isEmpty()) return List.of();
        if (perms.stream().noneMatch(p -> p.contains("*"))) return perms;
        Set<String> codes = catalogCodes();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(perms);
        for (String p : perms) {
            if (!p.contains("*")) continue;
            for (String c : codes) {
                if (globMatches(p, c)) out.add(c);
            }
        }
        return List.copyOf(out);
    }

    public static boolean globMatches(String pattern, String code) {
        if ("*".equals(pattern)) return true;
        String[] ps = pattern.split(":");
        String[] cs = code.split(":");
        for (int i = 0; i < ps.length; i++) {
            boolean last = i == ps.length - 1;
            if ("*".equals(ps[i])) {
                if (last) return cs.length >= ps.length;   // 末段通配：前缀下任意深度
                if (i >= cs.length) return false;
                continue;                                   // 中段通配：恰好一段
            }
            if (i >= cs.length || !ps[i].equals(cs[i])) return false;
        }
        return cs.length == ps.length;
    }

    private Set<String> catalogCodes() {
        if (catalog == null) return Set.of();
        long now = System.currentTimeMillis();
        if (now - catalogAt > CATALOG_TTL_MS) {
            catalogCache = catalog.allCodes();
            catalogAt = now;
        }
        return catalogCache;
    }

    /** 员工取在职档案的姓名；查不到（共享 admin、演示登录）或代理时用会话里的名字 */
    private String displayName(LoginUser u) {
        if (employees != null && u.realm() != null && "STAFF".equals(u.realm().name()) && u.userNo() != null) {
            var b = employees.briefsOf(List.of(u.userNo())).get(u.userNo());
            if (b != null && b.name() != null && !b.name().isBlank()) return b.name();
        }
        return u.username();
    }

    private static Map<String, Object> claims(LoginUser u, List<String> perms, String name) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("active", true);
        c.put("sub", u.userNo());
        c.put("name", name);
        c.put("tenant", u.tenantId());
        c.put("realm", u.realm().name());
        c.put("roles", u.role() == null || u.role().isBlank() ? List.of() : List.of(u.role()));
        c.put("perms", perms);
        c.put("scopes", scopes(u));
        return c;
    }

    /**
     * 数据范围 → OpsKit 范围标签（{@code 类型小写:编号}，如 {@code agent:AG0007}、{@code site:ST0311}）。
     * 全量范围（{@link DataScopeSpec#all()}）发 {@code ["*"]}（与 OpsKit 约定，2026-09-28）：**不发 = 没有范围**，
     * 只能看自己参与的与没打标签的单 —— 把「全部」写成不发，员工反而看不到打了标签的单。
     * 「*」只放开范围这一个条件；能否看全部审批 / 工单另由 OpsKit 的产品权限（角色）决定。
     */
    private static List<String> scopes(LoginUser u) {
        DataScopeSpec s = u.dataScope();
        List<String> out = new ArrayList<>();
        // 没有范围信息 → 当「没有范围」（fail-closed），不能当成全部放开
        if (s == null) return out;
        if (s.all()) return List.of("*");
        for (DataScopeSpec.Rule r : s.rules()) {
            String type = r.dim() == null ? "" : r.dim().toLowerCase(Locale.ROOT);
            for (String ref : r.refs()) out.add(type + ":" + ref);
        }
        return out;
    }

    /** Basic 凭据比对：两项都配了才可能通过；常量时间比较，避免按耗时猜出 secret */
    private boolean callerAllowed(String header) {
        if (clientId.isBlank() || clientSecret.isBlank()) return false;
        if (header == null || !header.startsWith("Basic ")) return false;
        String expected = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                header.substring(6).trim().getBytes(StandardCharsets.UTF_8));
    }

    private void write(HttpServletResponse resp, Map<String, Object> body) throws IOException {
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 内省结果不许被中间层缓存（OpsKit 自己按剩余有效期缓存）
        resp.setHeader("Cache-Control", "no-store");
        JSON.writeValue(resp.getOutputStream(), body);
    }
}
