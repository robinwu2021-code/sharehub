package ai.neargo.sharehub.cs;

/**
 * 工单式客服工单的状态（{@code cs_ticket.status}，[db-design §3.4]）。
 *
 * <p>⚠️ 与 {@code wo_order.status}（{@code WorkOrderStatus}，七个态）**不是一回事**，
 * 尽管两者都叫"工单"：那是运维工单（派单 / 接单 / 验收），这是客服工单（受理 / 处理 / 关闭）。
 * 也与 {@link CsSessionStatus} 不是一回事 —— 会话说的是"聊天还开着吗"。
 *
 * <p>三个词表都叫 status、且都含 {@code CLOSED}，正是全仓 {@code "CLOSED"} 散落 21 处
 * 却说不清各自指什么的由来。分开成三个枚举，才能在类型上回答"这个 CLOSED 是哪个 CLOSED"。
 */
public enum CsTicketStatus {

    /** 新建，待处理（DDL 默认值）。 */
    OPEN,
    /** 处理中。 */
    PROCESSING,
    /** 已关闭。 */
    CLOSED;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static CsTicketStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("客服工单状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("客服工单状态非法: " + v + "（仅 OPEN/PROCESSING/CLOSED）");
        }
    }
}
