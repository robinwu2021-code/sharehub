package ai.neargo.sharehub.wo.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.platform.event.AgentStatusChangedEvent;
import ai.neargo.sharehub.wo.ext.service.WoOpsService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 代理停用 → 名下未完结工单改派平台（对齐清单 F2 · E8）。事件在代理保存提交后投递，本侧写要 REQUIRES_NEW；
 * 不吞异常：失败由发件箱重投（改派后名下不再有代理工单，重跑是空操作）。
 */
@Component
public class AgentSuspendedListener {

    private final WoOpsService woOps;

    public AgentSuspendedListener(WoOpsService woOps) {
        this.woOps = woOps;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(AgentStatusChangedEvent e) {
        if (!e.suspended()) return;
        DataScopeContext.executeWithoutScope(() -> woOps.reassignFromAgent(e.agentNo(), e.reason()));
    }
}
