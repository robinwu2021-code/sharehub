package ai.neargo.sharehub.trade.price.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.platform.dto.SiteBrief;
import ai.neargo.sharehub.api.platform.port.SiteQueryPort;
import ai.neargo.sharehub.dev.port.PricingProbe;
import ai.neargo.sharehub.trade.price.engine.PriceQuery;
import ai.neargo.sharehub.trade.price.engine.PriceResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** {@link PricingProbe} 的实现：与下单取价同一套上下文（RentOrderServiceImpl.queryOf），否则门禁放行的设备下单时仍会取不到价。 */
@Component
public class PricingProbeImpl implements PricingProbe {

    private final PriceResolver resolver;
    private final ObjectProvider<SiteQueryPort> siteQuery;

    public PricingProbeImpl(PriceResolver resolver, ObjectProvider<SiteQueryPort> siteQuery) {
        this.resolver = resolver;
        this.siteQuery = siteQuery;
    }

    @Override
    public String planFor(String deviceType, String cabinetNo, String locationNo, String siteNo, String agentNo,
                          String vendorCode, String model) {
        String venueNo = null, sceneType = null, regionId = null, brandNo = null;
        SiteQueryPort port = siteQuery.getIfAvailable();
        if (port != null && siteNo != null) {
            SiteBrief b = DataScopeContext.executeWithoutScope(() -> port.briefsByNos(List.of(siteNo))).stream().findFirst().orElse(null);
            if (b != null) {
                venueNo = b.venueNo();
                sceneType = b.sceneType();
                regionId = b.regionId();
                brandNo = b.brandNo();
            }
        }
        PriceQuery q = new PriceQuery(deviceType, cabinetNo, locationNo, siteNo, venueNo, agentNo, sceneType, regionId,
                vendorCode, model, brandNo, LocalDateTime.now());
        PriceResolver.Hit hit = DataScopeContext.executeWithoutScope(() -> resolver.match(q));
        return hit == null ? null : hit.planNo();
    }
}
