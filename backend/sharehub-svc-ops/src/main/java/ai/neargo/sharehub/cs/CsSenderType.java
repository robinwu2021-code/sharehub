package ai.neargo.sharehub.cs;

/**
 * 客服消息的发送方（{@code cs_message.sender_type} 与 {@code cs_session.sender_type}，
 * [db-design §3.4]）。
 *
 * <p>⚠️ {@code AGENT} 在这里指**客服坐席**，而在 {@code agt_*} 那一族里指**代理商**
 * （{@code agent_no} / {@code AgtAgent}）。同一个词两个意思，是本项目 §1.0 术语裁定里
 * "商户"被禁用的同款风险 —— 收进枚举至少让它在类型上不会被当成代理商号使用。
 */
public enum CsSenderType {

    /** 消费者发的。 */
    USER,
    /** 客服坐席发的 —— **不是代理商**，见类注释。 */
    AGENT;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static CsSenderType of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("发送方必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("发送方非法: " + v + "（仅 USER/AGENT）");
        }
    }
}
