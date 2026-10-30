package ai.neargo.sharehub.dev.service;

import ai.neargo.sharehub.dev.CabinetStatus;
import ai.neargo.sharehub.dev.OnlineStatus;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 营收首页的设备提醒（需求方案 §10.2「提醒」）：在用设备里有几台离线、几台在线却没有可借电池。
 *
 * <p>两类互斥：离线的柜子可借数是上一次上报的旧值，算进「无可借」会重复报同一台。
 * 只看在用（{@code DEPLOYED}）—— 仓里、在途、报废的柜子离线是常态，不是要处理的事。
 * 数据范围由拦截器收窄（{@code dev_cabinet} 按商家锚点），商家只看到自己的。
 */
@Component
public class DeviceAlertQuery {

    /** 列表最多带回几台：提醒是让人点进去处理，不是设备台账。 */
    static final int SAMPLE = 20;

    public record DeviceAlerts(long deployed, long offline, long noBorrowable,
                               List<String> offlineSample, List<String> noBorrowableSample) {
    }

    private final CabinetMapper cabinets;

    public DeviceAlertQuery(CabinetMapper cabinets) {
        this.cabinets = cabinets;
    }

    public DeviceAlerts alerts() {
        long deployed = cabinets.selectCount(deployed());
        LambdaQueryWrapper<DevCabinet> off = deployed().eq(DevCabinet::getOnlineStatus, OnlineStatus.OFFLINE.name());
        LambdaQueryWrapper<DevCabinet> empty = deployed().eq(DevCabinet::getOnlineStatus, OnlineStatus.ONLINE.name())
                .and(w -> w.le(DevCabinet::getAvailableCount, 0).or().isNull(DevCabinet::getAvailableCount));
        return new DeviceAlerts(deployed, cabinets.selectCount(off), cabinets.selectCount(empty),
                sample(deployed().eq(DevCabinet::getOnlineStatus, OnlineStatus.OFFLINE.name())),
                sample(deployed().eq(DevCabinet::getOnlineStatus, OnlineStatus.ONLINE.name())
                        .and(w -> w.le(DevCabinet::getAvailableCount, 0).or().isNull(DevCabinet::getAvailableCount))));
    }

    private static LambdaQueryWrapper<DevCabinet> deployed() {
        return new LambdaQueryWrapper<DevCabinet>().eq(DevCabinet::getStatus, CabinetStatus.DEPLOYED.name());
    }

    private List<String> sample(LambdaQueryWrapper<DevCabinet> w) {
        return cabinets.selectList(w.select(DevCabinet::getCabinetNo).orderByAsc(DevCabinet::getCabinetNo)
                .last("limit " + SAMPLE)).stream().map(DevCabinet::getCabinetNo).toList();
    }
}
