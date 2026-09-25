package ai.neargo.sharehub.finance.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.platform.event.SiteClosedEvent;
import ai.neargo.sharehub.finance.service.AdjustmentService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * finance 订阅「站点已关闭」，生成撤场结清的结算调整项（C9）。
 * 同 {@link ContractSignedListener}：REQUIRES_NEW · 豁免数据范围 · 不吞异常（失败由发件箱重投，生成按唯一键幂等）。
 */
@Component
public class SiteClosedListener {

    private final AdjustmentService adjustments;

    public SiteClosedListener(AdjustmentService adjustments) {
        this.adjustments = adjustments;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(SiteClosedEvent e) {
        DataScopeContext.executeWithoutScope(() -> adjustments.onSiteClosed(e));
    }
}
