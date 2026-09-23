package ai.neargo.sharehub.cs;

/**
 * 在线客服会话的开闭（{@code cs_session.status}，[db-design §3.4]）。
 *
 * <p>与 {@link CsTicketStatus} 是**两个词表**：会话说的是"这个聊天还开着吗"，
 * 工单说的是"这个诉求处理到哪一步"。一个会话可以关掉而工单还开着，反之亦然。
 */
public enum CsSessionStatus {

    /** 会话进行中（DDL 默认值）。 */
    ACTIVE,
    /** 会话已结束。 */
    CLOSED;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static CsSessionStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("会话状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("会话状态非法: " + v + "（仅 ACTIVE/CLOSED）");
        }
    }
}
