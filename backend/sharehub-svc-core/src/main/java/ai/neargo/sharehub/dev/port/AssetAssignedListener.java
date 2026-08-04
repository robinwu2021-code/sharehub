package ai.neargo.sharehub.dev.port;

import ai.neargo.sharehub.api.core.port.DeviceOwnershipPort;
import ai.neargo.sharehub.api.platform.event.AssetAssignedEvent;
import ai.neargo.common.data.scope.DataScopeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * core 侧订阅「资产已划拨」，回写机柜归属（ADR-019 决策二）。
 *
 * <p><b>为什么是 REQUIRES_NEW</b>：本监听器在发布方事务**提交之后**执行
 * （{@code OutboxEventBus} 注册的 afterCommit 回调），此时已无活动事务。
 * 显式开新事务，让「站点下多台机柜」的回写要么全成要么全败 —— 部分回写比不回写更糟。
 *
 * <p><b>幂等</b>：回写是「把 agent_no 设为某值」的赋值语义，不是增量操作，
 * 重复投递结果相同。outbox 重投因此安全，无需额外去重表。
 *
 * <p><b>豁免数据范围</b>：级联必须完整。这里是系统按事件回写，没有「操作者」，
 * 若被 scope 过滤会产生部分级联 —— 授权闸门在发布方的 {@code requireVisible}，不在这里。
 */
@Component
public class AssetAssignedListener {

    private static final Logger log = LoggerFactory.getLogger(AssetAssignedListener.class);

    private final DeviceOwnershipPort deviceOwnership;

    public AssetAssignedListener(DeviceOwnershipPort deviceOwnership) {
        this.deviceOwnership = deviceOwnership;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(AssetAssignedEvent e) {
        int n = DataScopeContext.executeWithoutScope(() -> {
            if ("CABINET".equals(e.targetType())) {
                return deviceOwnership.reassignCabinet(e.cabinetNo(), e.agentNo());
            }
            if (e.locationNos() == null || e.locationNos().isEmpty()) {
                return 0;
            }
            return deviceOwnership.reassignByLocations(e.locationNos(), e.agentNo(), e.siteNo());
        });
        log.info("资产划拨回写机柜：{} {} → agent={} 影响 {} 行",
                e.targetType(), e.targetNo(), e.agentNo(), n);
    }
}
