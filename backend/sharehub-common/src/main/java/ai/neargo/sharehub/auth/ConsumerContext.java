package ai.neargo.sharehub.auth;

import org.springframework.security.access.AccessDeniedException;

/**
 * C 端（消费者）上下文门面。与运营端 {@link StaffContext} 分离——
 * C 端**无 RBAC，只属主鉴权**：业务经此取自己的 c_user_no / 断言资源属主，防横向越权（IDOR）。
 */
public final class ConsumerContext {

    private ConsumerContext() {
    }

    /** 当前 C 端主体；非 CONSUMER（未登录/运营端）→ 拒绝。 */
    public static LoginUser require() {
        LoginUser u = SecurityUtils.currentUser().orElse(null);
        if (u == null || u.realm() != Realm.CONSUMER) {
            throw new AccessDeniedException("非 C 端会话");
        }
        return u;
    }

    /** 当前消费者业务号（c_user_no）——服务端查询/过滤的唯一可信来源，不信前端传参。 */
    public static String userNo() {
        return require().userNo();
    }

    public static String tenantId() {
        return require().tenantId();
    }

    /** 属主断言：资源属主 ≠ 当前消费者 → 403（IDOR 防护）。 */
    public static void assertOwner(String ownerUserNo) {
        String me = userNo();
        if (ownerUserNo == null || !ownerUserNo.equals(me)) {
            throw new AccessDeniedException("无权访问他人资源");
        }
    }
}
