package ai.neargo.sharehub.wo.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.ops.port.WorkOrderCommandPort;
import ai.neargo.sharehub.wo.ext.WorkOrderType;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.WorkOrderDraft;
import ai.neargo.sharehub.wo.ext.service.WoOpsService;
import org.springframework.stereotype.Service;

/**
 * {@link WorkOrderCommandPort} 的本地实现。撤机单来源记 MANUAL（撤场是员工发起的动作，不是告警）；
 * 幂等靠 source_ref = {@code WD:站点:机柜} 的唯一约束（create 先查同键，已有即返回）。
 */
@Service
public class LocalWorkOrderCommand implements WorkOrderCommandPort {

    private final WoOpsService workOrders;

    public LocalWorkOrderCommand(WoOpsService workOrders) {
        this.workOrders = workOrders;
    }

    @Override
    public String openRemoval(String siteNo, String cabinetNo, String reason) {
        String key = "WD:" + siteNo + ":" + cabinetNo;
        return DataScopeContext.executeWithoutScope(() -> workOrders.create(new WorkOrderDraft(WorkOrderType.REMOVE.name(), "MANUAL",
                key, "MEDIUM", cabinetNo, null, null, null, null, "站点撤场撤机：" + reason, null)).woNo());
    }
}
