package ai.neargo.sharehub.cs.port;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.cs.entity.CsTicket;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link TicketWorkOrderPort} 的**占位实现**：只发一个 {@code WO} 号，<b>并不真的建工单</b>。
 *
 * <p>骨架期用它让 cs 域可独立编译与联调。真实现应落在 wo 域（建 {@code wo_order}，
 * {@code source=USER}、{@code source_ref=ticket_no}，走 {@code WoStateMachine} 初始态），
 * 并标 {@code @Primary} 顶掉本类。
 *
 * <p><b>上线前必须替换</b>：本类返回的号在 {@code wo_order} 里查无此单，
 * 会让「报障 → 工单进度」链路断在 C 端查询那一步。
 */
@Component
public class StubTicketWorkOrderPort implements TicketWorkOrderPort {
    private static final Logger log = LoggerFactory.getLogger(StubTicketWorkOrderPort.class);

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
        log.warn("  报障转工单是**桩实现**（StubTicketWorkOrderPort）：只发一个 WO-STUB-* 号，不建工单。");
        log.warn("  C 端会显示「已转维修工单 …」，而 wo_order 里查无此单 —— 没有人会去处理它。");
        log.warn("  真实现应落在 wo 域（source=USER、source_ref=ticket_no）并标 @Primary，本行随之消失。");
        log.warn("════════════════════════════════════════════════════════════");
    }


    @Override
    public String createWorkOrder(CsTicket ticket) {
        return BizKey.WORK_ORDER + "-STUB-" + ticket.getTicketNo();
    }
}
