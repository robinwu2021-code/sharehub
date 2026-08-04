package ai.neargo.sharehub.cs.port;

import ai.neargo.sharehub.cs.entity.CsTicket;

/**
 * 报障 → 工单的出站口（{@code cs_ticket.wo_no} 出口①）。
 *
 * <p><b>为什么是端口而不是直接写 wo_order</b>：工单聚合根（{@code wo} 域）有自己的状态机与
 * SLA 计算，cs 域直接 insert 会绕过它。用端口把「我需要一张工单」和「工单怎么建」分开，
 * cs 域只声明意图。
 *
 * <p><b>幂等责任在调用方</b>：{@code CsTicketService} 在调用本口之前会先检查
 * {@code ticket.woNo} 是否已非空 —— 非空即直接返回已有号，不进这里。
 * 另有第二道保险：[db-design §1.6] 要求 {@code wo_order.source_ref} 上有 UNIQUE，
 * 同一个 ticket_no 二次开单会被数据库拒。
 */
public interface TicketWorkOrderPort {

    /**
     * 为报障单开一张工单。
     *
     * @return 新建（或已存在）的 {@code wo_no}
     */
    String createWorkOrder(CsTicket ticket);
}
