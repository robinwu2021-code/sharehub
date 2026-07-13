package ai.neargo.powerbank.auth;

import ai.neargo.common.data.scope.DataScopeSpec;
import org.springframework.security.access.AccessDeniedException;

/**
 * 运营端（B 端：STAFF/AGENT）上下文门面。业务层与权限基建的接缝之一——
 * 运营端业务经此读功能权限/数据范围/代理号，**不碰 SecurityContext**，也与 C 端 {@link ConsumerContext} 分离。
 */
public final class StaffContext {

    private StaffContext() {
    }

    /** 当前运营端主体；非 STAFF/AGENT（如未登录或 C 端）→ 拒绝。 */
    public static LoginUser require() {
        LoginUser u = SecurityUtils.currentUser().orElse(null);
        if (u == null || u.realm() == Realm.CONSUMER) {
            throw new AccessDeniedException("非运营端会话");
        }
        return u;
    }

    /** 功能权限判定（通配 `*`/`device:*`），权限来自 Token 反查，绝不信客户端 X-Roles。 */
    public static boolean hasPerm(String code) {
        return SecurityUtils.currentUser().map(u -> u.hasPerm(code)).orElse(false);
    }

    public static boolean isAgent() {
        return SecurityUtils.realm() == Realm.AGENT;
    }

    /** 代理号（AGENT 才有；用于数据范围硬过滤，ADR-012）。 */
    public static String agentNo() {
        return SecurityUtils.currentUser().map(LoginUser::agentNo).orElse(null);
    }

    /** 当前有效数据范围（供 DataScopeHandler / 业务读取；无登录人=null）。 */
    public static DataScopeSpec dataScope() {
        return SecurityUtils.currentUser().map(LoginUser::dataScope).orElse(null);
    }
}
