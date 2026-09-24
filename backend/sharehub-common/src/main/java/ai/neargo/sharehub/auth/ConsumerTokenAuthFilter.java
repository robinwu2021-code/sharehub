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
 * C 端认证过滤器（与运营端 {@link StaffTokenAuthFilter} 分离）：{@code Bearer} → {@link TokenStore.SessionData}，
 * 只认 realm=CONSUMER，principal=LoginUser，authorities=ROLE_CONSUMER（C 端**无 RBAC**，授权靠
 * {@link ConsumerContext} 属主断言）。C 端无角色权限，无需口径 B 刷新。
 */
@Component
public class ConsumerTokenAuthFilter extends OncePerRequestFilter {

    private final TokenStore tokenStore;

    public ConsumerTokenAuthFilter(TokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        boolean scopeSet = false;
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            TokenStore.SessionData d = tokenStore.get(header.substring(7).trim()).orElse(null);
            if (d != null && d.user().realm() == Realm.CONSUMER) {
                var auth = new UsernamePasswordAuthenticationToken(
                        d.user(), null, List.of(new SimpleGrantedAuthority("ROLE_CONSUMER")));
                SecurityContextHolder.getContext().setAuthentication(auth);
                // 与 SecurityContext 成对写：后者只在 web 请求线程上有值，
                // 而 CurrentUser 让非 web 线程（@Async、定时任务）也读得到当前身份
                CurrentUser.set(d.user());
                DataScopeContext.set(d.user().dataScope());
                scopeSet = true;
            }
        }
        try {
            chain.doFilter(req, resp);
        } finally {
            if (scopeSet) {
                DataScopeContext.clear();
                // 线程池会复用线程 —— 不清的话下一个请求读到的不是「空身份」而是「别人的身份」
                CurrentUser.clear();
            }
        }
    }
}
