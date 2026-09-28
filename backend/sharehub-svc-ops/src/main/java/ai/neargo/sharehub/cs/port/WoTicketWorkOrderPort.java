package ai.neargo.sharehub.cs.port;

import ai.neargo.sharehub.cs.entity.CsTicket;
import ai.neargo.sharehub.wo.ext.WorkOrderType;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.WorkOrderDraft;
import ai.neargo.sharehub.wo.ext.service.WoOpsService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 报障转工单的真实现：在 {@code wo_order} 里**真的开一张单**。
 *
 * <p>在此之前装配的是一个只发号的占位实现：它返回 {@code WO-STUB-<ticketNo>}，
 * 而 {@code wo_order} 里查无此单 —— C 端明明白白显示「已转维修工单 WO-STUB-…」，
 * 运营的工单列表里却什么都没有，**没有人会去处理它**（2026-09-28 查实）。
 *
 * <p>几个刻意的选择：
 * <ul>
 *   <li><b>{@code source=USER}、{@code sourceNo=ticketNo}</b>：来源词表里 {@code USER} 就是为这条路留的；
 *       {@code source_ref} 上有 UNIQUE，于是「重复转工单」天然幂等 —— 第二次返回首张单，
 *       不会给同一条诉求开出两张单（{@code WoOpsServiceImpl#create} 自己保证）。</li>
 *   <li><b>类型固定 {@code FAULT}</b>：C 端报障说的都是「东西不对劲」。
 *       按问题字典再细分（补货 / 清洁）要等字典补上对应字段，那时改这里一处即可。</li>
 *   <li><b>不传归属三列</b>：{@code agent_no / site_no / location_no} 由 wo 侧从机柜反查 ——
 *       由调用方决定「这单归谁看」等于让调用方把单塞进任意代理的视野，或从该看到的人眼前藏起来。</li>
 *   <li><b>描述带上单号</b>：运维在工单里看到的第一句话就该告诉他这是哪张报障单转来的，
 *       否则他要回头去问客服。</li>
 * </ul>
 */
@Primary
@Component
// 报障工单的去处由开关定（M5-3）：LOCAL（默认）= 本地 wo_order；OPSKIT = OpsKitTicketWorkOrderPort
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "sharehub.flow.cs-ticket.engine",
        havingValue = "LOCAL", matchIfMissing = true)
public class WoTicketWorkOrderPort implements TicketWorkOrderPort {

    private final WoOpsService workOrders;

    public WoTicketWorkOrderPort(WoOpsService workOrders) {
        this.workOrders = workOrders;
    }

    @Override
    public String createWorkOrder(CsTicket ticket) {
        String issue = ticket.getIssue() == null || ticket.getIssue().isBlank() ? "（用户未填写描述）" : ticket.getIssue();
        WorkOrderDraft draft = new WorkOrderDraft(
                WorkOrderType.FAULT.name(), "USER", ticket.getTicketNo(), null,
                ticket.getCabinetNo(), null, null, null, null,
                "用户报障 " + ticket.getTicketNo() + "：" + issue, null);
        return workOrders.create(draft).woNo();
    }
}
