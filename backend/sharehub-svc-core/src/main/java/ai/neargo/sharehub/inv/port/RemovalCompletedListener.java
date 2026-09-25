package ai.neargo.sharehub.inv.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.ops.event.WorkOrderCompletedEvent;
import ai.neargo.sharehub.api.platform.dto.SiteBrief;
import ai.neargo.sharehub.api.platform.port.SiteQueryPort;
import ai.neargo.sharehub.dev.CabinetStatus;
import ai.neargo.sharehub.dev.PowerbankStatus;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.entity.DevPowerbank;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.mapper.PowerbankMapper;
import ai.neargo.sharehub.dev.service.CabinetLifecycleService;
import ai.neargo.sharehub.inv.service.TransferOpsService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 撤机完工（对齐清单 C8）与巡检清点（D5）。撤机：现场清点数与系统在柜数比对 → 不符挂资产差异；机柜撤机（解绑点位 / 站点，回在库）；
 * 生成从站点回仓的调拨草稿（签收时逐件核对）。
 *
 * <p>三步同一事务（REQUIRES_NEW：事件在工单事务提交后投递）。任何一步失败整体回滚、抛出让发件箱重投 ——
 * 工单已完工而柜子还挂在站点上，站点就永远关不掉；重投仍失败的要有人介入。
 */
@Component
public class RemovalCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(RemovalCompletedListener.class);
    private static final String REMOVE = "REMOVE";
    private static final String INSPECT = "INSPECT";
    private static final Set<String> ON_SITE = Set.of(CabinetStatus.DEPLOYED.name(), CabinetStatus.FAULT.name());

    private final CabinetMapper cabinets;
    private final PowerbankMapper powerbanks;
    private final CabinetLifecycleService lifecycle;
    private final TransferOpsService transfers;
    private final SiteQueryPort sites;

    public RemovalCompletedListener(CabinetMapper cabinets, PowerbankMapper powerbanks, CabinetLifecycleService lifecycle,
                                    TransferOpsService transfers, SiteQueryPort sites) {
        this.cabinets = cabinets;
        this.powerbanks = powerbanks;
        this.lifecycle = lifecycle;
        this.transfers = transfers;
        this.sites = sites;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(WorkOrderCompletedEvent e) {
        if (e.cabinetNo() == null) return;
        // 巡检清点（D5）：只比对、挂差异，柜子留在原地 —— 巡检兼做资产盘点
        if (INSPECT.equals(e.type())) {
            if (e.countedQty() != null) DataScopeContext.executeWithoutScope(() -> compare(e, "INSPECTION"));
            return;
        }
        if (!REMOVE.equals(e.type())) return;
        try {
            DataScopeContext.executeWithoutScope(() -> {
                handle(e);
                return null;
            });
        } catch (RuntimeException ex) {
            log.error("撤机完工后续处理失败 woNo={} cabinetNo={}：发件箱会重投；持续失败则柜子仍挂在站点上、站点关不掉，"
                    + "请在设备页手动撤机并建回仓调拨", e.woNo(), e.cabinetNo(), ex);
            throw ex;   // 整体回滚 + 发件箱标 FAILED 重投（三步都幂等：已撤不再撤、回仓单按工单号去重、差异随回滚一起撤销）
        }
    }

    /** 清点比对：有宝台账按台账（在柜），没有按设备上报的可借数；不符落一条数量差异。 */
    private Void compare(WorkOrderCompletedEvent e, String sourceType) {
        if (e.countedQty() == null) return null;
        DevCabinet c = cabinets.selectOne(new LambdaQueryWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, e.cabinetNo()).last("limit 1"));
        if (c == null) return null;
        Long tracked = powerbanks.selectCount(new LambdaQueryWrapper<DevPowerbank>()
                .eq(DevPowerbank::getCabinetNo, c.getCabinetNo()).eq(DevPowerbank::getStatus, PowerbankStatus.IN_CABINET.name()));
        int expected = tracked != null && tracked > 0 ? tracked.intValue() : c.getAvailableCount() == null ? 0 : c.getAvailableCount();
        if (e.countedQty() != expected) {
            transfers.recordCountMismatch(sourceType, e.woNo(), c.getSiteNo() != null ? c.getSiteNo() : e.siteNo(), c.getCabinetNo(),
                    expected, e.countedQty());
            log.warn("清点不符 source={} woNo={} cabinetNo={} 系统 {} 颗 / 现场 {} 颗，已落资产差异，请仓管追查",
                    sourceType, e.woNo(), c.getCabinetNo(), expected, e.countedQty());
        }
        return null;
    }

    private void handle(WorkOrderCompletedEvent e) {
        DevCabinet c = cabinets.selectOne(new LambdaQueryWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, e.cabinetNo()).last("limit 1"));
        if (c == null) return;
        String siteNo = c.getSiteNo() != null ? c.getSiteNo() : e.siteNo();
        // 1) 清点比对
        compare(e, "REMOVAL");
        // 2) 撤机：解绑点位与站点，回在库（已经撤过的不重复）
        if (ON_SITE.contains(c.getStatus())) lifecycle.undeploy(c.getCabinetNo(), "撤场撤机（工单 " + e.woNo() + "）");
        // 3) 回仓调拨草稿：发货 / 签收仍由人操作，签收时逐件核对
        String siteName = siteNo == null ? null
                : sites.briefsByNos(List.of(siteNo)).stream().findFirst().map(SiteBrief::name).orElse(null);
        if (siteNo != null) transfers.openReturn(siteNo, siteName, c.getCabinetNo(), e.woNo());
    }
}
