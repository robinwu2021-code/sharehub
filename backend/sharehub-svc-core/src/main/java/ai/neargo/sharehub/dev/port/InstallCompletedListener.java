package ai.neargo.sharehub.dev.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.ops.event.WorkOrderCompletedEvent;
import ai.neargo.sharehub.dev.CabinetStatus;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.service.CabinetLifecycleService;
import ai.neargo.sharehub.dev.service.CabinetService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 装机工单驱动布放（对齐清单 C5）：装机单完工 →（带了扫码点位且与档案不同则改绑）→ 上线门禁全过即自动上线。
 *
 * <p>门禁不过就保持在库、不报错：装机完工只说明「装好了」，试借还、合同、装宝比例这些仍由门禁逐项说话，
 * 运营在设备页看门禁就知道还差什么。事件在工单事务提交后投递，本侧的写要 REQUIRES_NEW。
 */
@Component
public class InstallCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(InstallCompletedListener.class);
    private static final String INSTALL = "INSTALL";

    private final CabinetMapper cabinets;
    private final CabinetService cabinetService;
    private final CabinetLifecycleService lifecycle;

    public InstallCompletedListener(CabinetMapper cabinets, CabinetService cabinetService, CabinetLifecycleService lifecycle) {
        this.cabinets = cabinets;
        this.cabinetService = cabinetService;
        this.lifecycle = lifecycle;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(WorkOrderCompletedEvent e) {
        if (!INSTALL.equals(e.type()) || e.cabinetNo() == null) return;
        try {
            DataScopeContext.executeWithoutScope(() -> {
                DevCabinet c = cabinets.selectOne(new LambdaQueryWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, e.cabinetNo()).last("limit 1"));
                if (c == null || !CabinetStatus.IN_STOCK.name().equals(c.getStatus())) return null;
                if (e.locationNo() != null && !e.locationNo().equals(c.getLocationNo())) {
                    // 现场扫到的点位与档案不同：以现场为准改绑（改绑会让之前的试借还失效，门禁会提示重做）
                    cabinetService.save(e.cabinetNo(), Map.of("locationNo", e.locationNo()));
                    log.info("装机扫码改绑点位 cabinetNo={} {} → {}（工单 {}）", e.cabinetNo(), c.getLocationNo(), e.locationNo(), e.woNo());
                }
                lifecycle.goLiveIfReady(e.cabinetNo(), "装机工单 " + e.woNo());
                return null;
            });
        } catch (RuntimeException ex) {
            // 工单已完工、事务已提交：这里失败只是没自动上线，运营仍可在设备页手动上线。改绑与上线一起回滚
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            log.warn("装机完工后的自动上线失败 woNo={} cabinetNo={}，请在设备页查看上线门禁后手动上线", e.woNo(), e.cabinetNo(), ex);
        }
    }
}
