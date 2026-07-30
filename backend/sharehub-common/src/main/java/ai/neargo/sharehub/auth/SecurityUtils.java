package ai.neargo.powerbank.auth;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * 共享基座：realm 无关的当前身份读取（运营端/C 端共用的**基础基建**）。
 *
 * <p>只暴露两端都通用的最小身份信息；**运营端 RBAC/数据权限走 {@link StaffContext}，
 * C 端属主走 {@link ConsumerContext}**（两端上下文分离）。业务层一律经这些工具读取，
 * 不直接依赖 {@code SecurityContextHolder}——把 Spring Security 细节收敛于此（业务/权限解耦）。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /** 当前登录主体（未认证=empty）；principal 统一是 {@link LoginUser}，由各端过滤器写入。 */
    public static Optional<LoginUser> currentUser() {
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
