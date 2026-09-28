package ai.neargo.opskit.ticket.core.app;

import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.process.client.InstanceObserver;

/** 流程实例变化 → 同事务同步工单快照（21/02 §8.6「快照同步」）。 */
public class TicketObserver implements InstanceObserver {

    private final TicketAppService tickets;

    public TicketObserver(TicketAppService tickets) {
        this.tickets = tickets;
    }

    @Override
    public void onInstanceChanged(TenantContext t, String instanceNo) {
        tickets.syncByInstance(t, instanceNo);
    }
}
