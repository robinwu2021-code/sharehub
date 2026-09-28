package ai.neargo.sharehub.api.platform.flow;

/**
 * 业务侧接收 OpsKit 工单进展的 SPI（M5-3）。每个送单的业务实现一个，按 {@link #bizType()} 路由。
 * 回调可能重复到达（至少一次投递）：实现必须幂等。
 */
public interface TicketOutcomeHandler {

    /** 与 {@link TicketRequestedEvent#bizType()} 相同 */
    String bizType();

    /** OpsKit 已建单（或并入了已有的未结单，attached=true）：回填工单号 */
    void onTicketCreated(String bizNo, String ticketNo, boolean attached);

    /** 送单重投达上限（死信）：业务侧给出「送单失败」让人重试 */
    void onTicketFailed(String bizNo, String error);
}
