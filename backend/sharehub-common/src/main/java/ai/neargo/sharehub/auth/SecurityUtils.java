package ai.neargo.sharehub.auth;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * 共享基座：realm 无关的当前身份读取（运营端/C 端共用的**基础基建**）。
 *
 * <p><b>2026-09-24</b>：身份的真源改为 {@link CurrentUser}（纯 ThreadLocal，零 Spring 依赖），
 * SecurityContextHolder 降为回落。这让定时任务、outbox 投递、@Async 这些**非 web 线程**
 * 也能读到当前身份 —— 此前它们只能把 operator 一路当参数往下传。
 *
 * <p>只暴露两端都通用的最小身份信息；**运营端 RBAC/数据权限走 {@link StaffContext}，
 * C 端属主走 {@link ConsumerContext}**（两端上下文分离）。业务层一律经这些工具读取，
 * 不直接依赖 {@code SecurityContextHolder}——把 Spring Security 细节收敛于此（业务/权限解耦）。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * 当前登录主体（未认证=empty）。
     *
     * <p><b>先读 {@link CurrentUser} 这个纯 ThreadLocal，再回落 SecurityContextHolder。</b>
     * 顺序不能反，原因是两者的覆盖面不同：
     * <ul>
     *   <li>{@code SecurityContextHolder} 只在「被 Spring Security 过滤链处理过的 web 请求线程」上有值 ——
     *       定时任务、outbox 投递、{@code @Async}、MyBatis 拦截器里一律是空；</li>
     *   <li>{@link CurrentUser} 是普通 ThreadLocal，谁都能设、谁都能读，
     *       且能用 {@code runWith} 显式带到别的线程。</li>
     * </ul>
     * 回落保留是为了兼容：万一某条链路只设了 SecurityContext（比如
     * {@code InternalTokenFilter} 那种直接 new UsernamePasswordAuthenticationToken 的），
     * 业务侧照样读得到。<b>两者都没有才算未认证。</b>
     */
    public static Optional<LoginUser> currentUser() {
        Optional<LoginUser> fromHolder = CurrentUser.get();
        if (fromHolder.isPresent()) return fromHolder;
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.isAuthenticated() && a.getPrincipal() instanceof LoginUser u) {
            return Optional.of(u);
        }
        return Optional.empty();
    }

    /** 必须已登录，否则拒绝（安全网；正常已被过滤器拦下）。 */
    public static LoginUser requireUser() {
        return currentUser().orElseThrow(() -> new AccessDeniedException("未认证或会话失效"));
    }

    public static String userNo() {
        return currentUser().map(LoginUser::userNo).orElse(null);
    }

    public static String tenantId() {
        return currentUser().map(LoginUser::tenantId).orElse("MAIN");
    }

    public static Realm realm() {
        return currentUser().map(LoginUser::realm).orElse(null);
    }
}
