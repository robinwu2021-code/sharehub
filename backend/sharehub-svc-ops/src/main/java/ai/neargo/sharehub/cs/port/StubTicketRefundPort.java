package ai.neargo.sharehub.cs.port;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.cs.entity.CsTicket;
import org.springframework.stereotype.Component;

/**
 * {@link TicketRefundPort} 的**占位实现**：只发一个 {@code RFD} 号，<b>并不真的建退款申请</b>。
 *
 * <p>真实现应落在 trade 域（建 {@code ord_refund}，带 {@code idempotency_key}，进财务审批队列），
 * 并标 {@code @Primary} 顶掉本类。
 *
 * <p><b>上线前必须替换</b>：本类会让报障单看起来「已转退款」而财务侧没有任何待办 —— 钱不会到用户手上。
 */
@Component
public class StubTicketRefundPort implements TicketRefundPort {

    @Override
    public String createRefund(CsTicket ticket) {
        return BizKey.REFUND + "-STUB-" + ticket.getTicketNo();
    }
}
