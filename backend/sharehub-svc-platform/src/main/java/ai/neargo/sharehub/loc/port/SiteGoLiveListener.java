package ai.neargo.sharehub.loc.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.core.event.CabinetWentLiveEvent;
import ai.neargo.sharehub.loc.service.SiteService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 首台设备上线 → 站点营业（联动 E6）。条件更新 {@code WHERE status='PREPARING'}，重复事件无副作用。
 * 不吞异常：失败交给 Outbox 与每小时对账（{@code site-golive-reconcile}）。
 */
@Component
public class SiteGoLiveListener {

    private final SiteService sites;

    public SiteGoLiveListener(SiteService sites) {
        this.sites = sites;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(CabinetWentLiveEvent e) {
        DataScopeContext.executeWithoutScope(() -> sites.goLiveIfPreparing(e.siteNo(), e.at() == null ? null : java.time.LocalDateTime.parse(e.at()), "cabinet " + e.cabinetNo()));
    }
}
