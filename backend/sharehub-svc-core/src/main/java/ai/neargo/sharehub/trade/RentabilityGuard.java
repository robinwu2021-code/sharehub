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
import java.util.Optional;

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
        blockedReason(c.getCabinetNo(), c.getSiteNo(), c.getStatus(), c.getOnlineStatus(), c.getAvailableCount())
                .ifPresent(key -> {
                    throw ai.neargo.sharehub.common.BizException.conflict(key);
                });
    }

    /**
     * 借不出来的原因（i18n key）；能借则 {@link Optional#empty()}。
     *
     * <h3>为什么要有这个「不抛异常的版本」</h3>
     * 「这儿能不能借」此前在**三个地方各算一遍**，而且三处都不一样：
     * <ul>
     *   <li>找柜列表：只看 {@code availableCount}；</li>
     *   <li>机柜详情：{@code availableCount>0 && ONLINE && status!=FAULT}；</li>
     *   <li>借出本身（本类）：站点可租 + {@code DEPLOYED} + 在线 + 未整柜停借 + 有货。</li>
     * </ul>
     * 于是 2026-09-27 H5 对接实测看到：停用站点（Dubai Mall L1）在首页列着「可借 7」，
     * 点进去借才撞 409「该站点暂停服务」。三处各自都说得通，合起来是用户白跑一趟。
     *
     * <p>展示层要的是布尔、借出要的是异常，但**判据只能有一份**。所以判断放这里，
     * {@link #checkRent} 是它的抛异常视图，展示层调它取布尔。
     */
    public Optional<String> blockedReason(String cabinetNo, String siteNo, String status,
                                          String onlineStatus, Integer availableCount) {
        SiteQueryPort port = siteQuery.getIfAvailable();
        if (port != null && siteNo != null) {
            SiteBrief site = DataScopeContext.executeWithoutScope(() -> port.briefsByNos(List.of(siteNo)))
                    .stream().findFirst().orElse(null);
            if (site != null && !site.rentable()) {
                return Optional.of("error.rent.site_paused");
            }
        }
        if (!CabinetStatus.DEPLOYED.name().equals(status) || !OnlineStatus.ONLINE.name().equals(onlineStatus)) {
            return Optional.of("error.rent.cabinet_unavailable");
        }
        Long stop = DataScopeContext.executeWithoutScope(() -> protections.selectCount(new LambdaQueryWrapper<DevProtection>()
                .eq(DevProtection::getCabinetNo, cabinetNo).eq(DevProtection::getAction, ProtectionAction.STOP_RENT.name())
                .eq(DevProtection::getActive, 1)));
        if (stop != null && stop > 0) return Optional.of("error.rent.cabinet_unavailable");
        if (availableCount != null && availableCount <= 0) {
            return Optional.of("error.rent.no_stock");
        }
        return Optional.empty();
    }
}
