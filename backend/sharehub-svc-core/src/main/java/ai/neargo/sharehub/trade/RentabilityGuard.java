package ai.neargo.sharehub.trade;

import ai.neargo.common.core.ErrorCode;
import ai.neargo.common.core.ServerException;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.platform.dto.SiteBrief;
import ai.neargo.sharehub.api.platform.port.SiteQueryPort;
import ai.neargo.sharehub.dev.CabinetStatus;
import ai.neargo.sharehub.dev.OnlineStatus;
import ai.neargo.sharehub.dev.ProtectionAction;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.entity.DevProtection;
import ai.neargo.sharehub.dev.mapper.DeviceOpsMappers.ProtectionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 借出校验（停借保还，TDD-运营核心流程/04 §4.4）：只有「营业中站点 × 已布放在线柜 × 未被整柜停借 × 有可借宝」能借。
 * <b>归还路径不调用</b> —— 停业、撤场、故障都不该把用户的充电宝扣在手里。
 *
 * <p>在线口径取网关维护的 {@code online_status}；「心跳超过 3 分钟即视为离线」由业务告警的定时判定推断，
 * 并以告警持有者的身份申请整柜 STOP_RENT —— 在这里重复推断会让两处口径分叉。
 */
@Component
public class RentabilityGuard {

    private final ObjectProvider<SiteQueryPort> siteQuery;
    private final ProtectionMapper protections;

    public RentabilityGuard(ObjectProvider<SiteQueryPort> siteQuery, ProtectionMapper protections) {
        this.siteQuery = siteQuery;
        this.protections = protections;
    }

    public void checkRent(DevCabinet c) {
        if (c == null) throw ai.neargo.sharehub.common.BizException.conflict("error.rent.cabinet_unavailable");
        SiteQueryPort port = siteQuery.getIfAvailable();
        if (port != null && c.getSiteNo() != null) {
            SiteBrief site = DataScopeContext.executeWithoutScope(() -> port.briefsByNos(List.of(c.getSiteNo())))
                    .stream().findFirst().orElse(null);
            if (site != null && !site.rentable()) {
                throw ai.neargo.sharehub.common.BizException.conflict("error.rent.site_paused");
            }
        }
        if (!CabinetStatus.DEPLOYED.name().equals(c.getStatus()) || !OnlineStatus.ONLINE.name().equals(c.getOnlineStatus())) {
            throw ai.neargo.sharehub.common.BizException.conflict("error.rent.cabinet_unavailable");
        }
        Long stop = DataScopeContext.executeWithoutScope(() -> protections.selectCount(new LambdaQueryWrapper<DevProtection>()
                .eq(DevProtection::getCabinetNo, c.getCabinetNo()).eq(DevProtection::getAction, ProtectionAction.STOP_RENT.name())
                .eq(DevProtection::getActive, 1)));
        if (stop != null && stop > 0) throw ai.neargo.sharehub.common.BizException.conflict("error.rent.cabinet_unavailable");
        if (c.getAvailableCount() != null && c.getAvailableCount() <= 0) {
            throw ai.neargo.sharehub.common.BizException.conflict("error.rent.no_stock");
        }
    }
}
