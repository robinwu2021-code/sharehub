package ai.neargo.sharehub.dev.port;

import ai.neargo.sharehub.api.core.port.DeviceOwnershipPort;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link DeviceOwnershipPort} 的本地实现，住在 core 侧（`dev` 包）。
 *
 * <p>机柜的 `agent_no`/`site_no` 只在这里被外部触发修改；
 * 其余归属变更走机柜自己的业务入口。
 */
@Service
public class LocalDeviceOwnership implements DeviceOwnershipPort {

    private final CabinetMapper cabinets;

    public LocalDeviceOwnership(CabinetMapper cabinets) {
        this.cabinets = cabinets;
    }

    @Override
    public int reassignByLocations(List<String> locationNos, String agentNo, String siteNo) {
        if (locationNos == null || locationNos.isEmpty()) return 0;
        LambdaUpdateWrapper<DevCabinet> w = new LambdaUpdateWrapper<DevCabinet>()
                .in(DevCabinet::getLocationNo, locationNos)
                .set(DevCabinet::getAgentNo, agentNo);
        // siteNo 为 null 表示调用方不掌握站点（按点位划拨），此时不动冗余列
        if (siteNo != null) w.set(DevCabinet::getSiteNo, siteNo);
        return cabinets.update(null, w);
    }

    @Override
    public boolean existsInScope(String cabinetNo) {
        // 保留数据范围：MyBatis 拦截器按当前会话注入过滤，看不到即返回 false
        return cabinets.exists(new LambdaQueryWrapper<DevCabinet>()
                .eq(DevCabinet::getCabinetNo, cabinetNo));
    }

    @Override
    public int reassignCabinet(String cabinetNo, String agentNo) {
        return cabinets.update(null, new LambdaUpdateWrapper<DevCabinet>()
                .eq(DevCabinet::getCabinetNo, cabinetNo)
                .set(DevCabinet::getAgentNo, agentNo));
    }
}
