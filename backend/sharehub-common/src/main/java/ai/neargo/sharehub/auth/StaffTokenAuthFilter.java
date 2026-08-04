package ai.neargo.sharehub.auth;

import ai.neargo.common.data.scope.DataScopeContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 运营端（B 端）认证过滤器：{@code Bearer} → {@link TokenStore.SessionData} → {@link LoginUser} → authorities=权限码。
 * 只认 STAFF/AGENT 会话。**红线**：只认 token 反查权限，忽略客户端 X-Roles/X-User-Id。
 *
 * <p><b>口径 B（改权限即时生效）</b>：会话戳 ≠ 全局 {@link PermVersion} 时，用 {@link PrincipalRefresher#rebuild}
 * 按会话角色重载权限/数据范围并原地刷新会话——在线员工下一请求即收敛新权限。
 * 依赖 infra 接口 {@link PrincipalRefresher}（非 IAM 业务 PermissionService），保证分层 infra ← iam（ADR-015）。
 */
@Component
public class StaffTokenAuthFilter extends OncePerRequestFilter {

    private final TokenStore tokenStore;
    private final PrincipalRefresher refresher;
    private final PermVersion permVersion;

    public StaffTokenAuthFilter(TokenStore tokenStore, PrincipalRefresher refresher, PermVersion permVersion) {
        this.tokenStore = tokenStore;
        this.refresher = refresher;
        this.permVersion = permVersion;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        boolean scopeSet = false;
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            TokenStore.SessionData d = tokenStore.get(token).orElse(null);
            if (d != null && d.user().realm() != Realm.CONSUMER) {
                LoginUser u = d.user();
                if (d.permStamp() != permVersion.get()) {             // 口径 B：权限变更 → 重建刷新
                    u = refresher.rebuild(u, d.roleNos());
                    tokenStore.refresh(token, new TokenStore.SessionData(u, d.roleNos(), permVersion.get()));
                }
                List<SimpleGrantedAuthority> authorities = u.perms().stream()
                        .map(SimpleGrantedAuthority::new).toList();
                var auth = new UsernamePasswordAuthenticationToken(u, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
                DataScopeContext.set(u.dataScope());                  // 供 DataScopeHandler 注入 SQL
                // 原始 token 另存一份供跨服务透传：SecurityContext 里只有解析后的 LoginUser，
                // token 本身已经不在了。**透传 token 而非身份声明**，红线（不信客户端 X-Roles）不破。
                CallContext.setToken(token);
                scopeSet = true;
            }
        }
        try {
            chain.doFilter(req, resp);
        } finally {
            if (scopeSet) {
                DataScopeContext.clear();
                // 线程池复用下不清会把上一个请求的身份泄露给下一个
                CallContext.clear();
            }
        }
    }
}
