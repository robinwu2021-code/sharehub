package ai.neargo.sharehub.dev.port;

import ai.neargo.sharehub.api.core.port.DeviceOwnershipPort;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.movement.MovementEvent;
import ai.neargo.sharehub.dev.movement.MovementRecorder;
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
    private final MovementRecorder movements;

    public LocalDeviceOwnership(CabinetMapper cabinets, MovementRecorder movements) {
        this.cabinets = cabinets;
        this.movements = movements;
    }

    @Override
    public int reassignByLocations(List<String> locationNos, String agentNo, String siteNo) {
        if (locationNos == null || locationNos.isEmpty()) return 0;
        LambdaUpdateWrapper<DevCabinet> w = new LambdaUpdateWrapper<DevCabinet>()
                .in(DevCabinet::getLocationNo, locationNos)
                .set(DevCabinet::getAgentNo, agentNo)
                // 整站换商家（V165）：站上已绑定的设备一并转进新商家的出库池；收回为直营则离开出库池（I5）。
                // 不同步的话「绑了商家点位必在出库池」（chk_cab_bound_issued）与 I2 都会被打破
                .set(DevCabinet::getIssuedAgentNo, agentNo);
        // siteNo 为 null 表示调用方不掌握站点（按点位划拨），此时不动冗余列
        if (siteNo != null) w.set(DevCabinet::getSiteNo, siteNo);
        // 履历（V196）：先查出受影响的柜子，逐台记一行「换商家」
        List<DevCabinet> moved = cabinets.selectList(new LambdaQueryWrapper<DevCabinet>().in(DevCabinet::getLocationNo, locationNos));
        int n = cabinets.update(null, w);
        for (DevCabinet c : moved) {
            if (java.util.Objects.equals(c.getAgentNo(), agentNo)) continue;
            String at = MovementRecorder.loc(c.getLocationNo());
            movements.record(new MovementRecorder.Move(MovementRecorder.CABINET, c.getCabinetNo(), MovementEvent.REASSIGN, at, at,
                    c.getAgentNo(), agentNo, "整站换商家", siteNo));
        }
        return n;
    }

    @Override
    public boolean existsInScope(String cabinetNo) {
        // 保留数据范围：MyBatis 拦截器按当前会话注入过滤，看不到即返回 false
        return cabinets.exists(new LambdaQueryWrapper<DevCabinet>()
                .eq(DevCabinet::getCabinetNo, cabinetNo));
    }

    @Override
    public int reassignCabinet(String cabinetNo, String agentNo) {
        DevCabinet c = cabinets.selectOne(new LambdaQueryWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, cabinetNo).last("limit 1"));
        int n = cabinets.update(null, new LambdaUpdateWrapper<DevCabinet>()
                .eq(DevCabinet::getCabinetNo, cabinetNo)
                .set(DevCabinet::getAgentNo, agentNo));
        if (n > 0 && c != null && !java.util.Objects.equals(c.getAgentNo(), agentNo)) {
            String at = c.getLocationNo() != null ? MovementRecorder.loc(c.getLocationNo()) : null;
            movements.record(new MovementRecorder.Move(MovementRecorder.CABINET, cabinetNo, MovementEvent.REASSIGN, at, at,
                    c.getAgentNo(), agentNo, null, null));
        }
        return n;
    }
}
