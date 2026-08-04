package ai.neargo.sharehub.cs.port;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.cs.entity.CsTicket;
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

    @Override
    public String createWorkOrder(CsTicket ticket) {
        return BizKey.WORK_ORDER + "-STUB-" + ticket.getTicketNo();
    }
}
