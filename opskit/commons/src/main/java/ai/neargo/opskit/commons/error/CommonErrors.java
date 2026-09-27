package ai.neargo.opskit.commons.error;

/** 各模块共用的错误键（06 §七）。模块特有的放在各自的 *Errors 里。 */
public final class CommonErrors {

    public static final ErrorKey STATE_CHANGED = new ErrorKey("opskit.state_changed", 409);
    public static final ErrorKey IDEMPOTENCY_MISMATCH = new ErrorKey("opskit.idempotency.mismatch", 409);
    /** 凭证 / 令牌缺失、格式错、已失效。不区分哪一种，不给探测者线索。 */
    public static final ErrorKey UNAUTHENTICATED = new ErrorKey("opskit.unauthenticated", 401);
    public static final ErrorKey PERMISSION_DENIED = new ErrorKey("opskit.permission.denied", 403);
    /** 不存在、跨空间、不可见一律用它，不泄露存在性。 */
    public static final ErrorKey NOT_FOUND = new ErrorKey("opskit.not_found", 404);
    public static final ErrorKey VALIDATION = new ErrorKey("opskit.validation", 422);
    public static final ErrorKey QUOTA_EXCEEDED = new ErrorKey("opskit.quota.exceeded", 429);

    private CommonErrors() {
    }
}
