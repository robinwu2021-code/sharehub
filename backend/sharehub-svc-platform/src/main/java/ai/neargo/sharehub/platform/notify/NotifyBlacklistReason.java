package ai.neargo.sharehub.platform.notify;

/**
 * 触达拉黑原因（{@code notify_blacklist.reason}）。
 *
 * <p>这一列是**合规凭据**：{@link #USER_OPT_OUT} 与 {@link #HARD_BOUNCE} 的拉黑不能被
 * 运营随手解除，而 {@link #MANUAL} 可以 —— 原因写错等于把「用户明确退订」
 * 降级成「运营手工拉黑」，然后合法地再给他发。这类错不会报错。
 *
 * <p>取值以 DDL 列注释为真源。写入口是 {@code POST /api/platform/notify-blacklist}，
 * 它直接收实体当请求体（见 {@code known-entity-request-bodies.txt}），
 * 所以校验落在 service 而不是 DTO 上。
 */
public enum NotifyBlacklistReason {

    /** 用户明确退订。 */
    USER_OPT_OUT,
    /** 硬退信 / 号码不可达。 */
    HARD_BOUNCE,
    /** 滥用（投诉、被举报）。 */
    ABUSE,
    /** 运营手工拉黑。 */
    MANUAL;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static NotifyBlacklistReason of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("拉黑原因必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("拉黑原因非法: " + v
                    + "（仅 USER_OPT_OUT/HARD_BOUNCE/ABUSE/MANUAL）");
        }
    }
}
