package ai.neargo.sharehub.cs.port;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.cs.entity.CsTicket;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(StubTicketRefundPort.class);

    /**
     * 照 {@code StubPaymentPort} 的做法在启动日志里喊一声。
     *
     * <p>本桩此前<b>完全静默</b>：它是唯一实现、无条件装配，于是被一路带到生产而没有任何症状 ——
     * 症状全落在用户那一侧（见类注释），而看日志的人一无所知。
     * 模拟成功的东西必须在日志里显形。
     */
    @PostConstruct
    void warn() {
        log.warn("════════════════════════════════════════════════════════════");
        log.warn("  报障转退款是**桩实现**（StubTicketRefundPort）：只发一个 RFD-STUB-* 号，不建退款申请。");
        log.warn("  C 端会显示「已转退款 …」，而财务侧没有任何待办 —— 钱不会到用户手上。");
        log.warn("  真实现应落在 trade 域（ord_refund + 幂等键，进财务审批队列）并标 @Primary，本行随之消失。");
        log.warn("════════════════════════════════════════════════════════════");
    }


    @Override
    public String createRefund(CsTicket ticket) {
        return BizKey.REFUND + "-STUB-" + ticket.getTicketNo();
    }
}
