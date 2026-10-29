package ai.neargo.sharehub.inv.port;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.dev.CabinetStatus;
import ai.neargo.sharehub.dev.PowerbankStatus;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.entity.DevPowerbank;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.mapper.PowerbankMapper;
import ai.neargo.sharehub.dev.movement.MovementEvent;
import ai.neargo.sharehub.dev.movement.MovementRecorder;
import ai.neargo.sharehub.dev.service.CabinetPoolService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 本地库存引擎（独立进销存上线前）：库存账就是设备表上的「所在仓 / 出库池」两列。
 * 出库、签收、调整、退回委托 {@link CabinetPoolService}；入库 = 把所在仓写成待检单的仓。
 */
@Component
public class LocalInventoryPort implements InventoryPort {

    private static final String CABINET = MovementRecorder.CABINET;

    private final CabinetPoolService pool;
    private final CabinetMapper cabinets;
    private final PowerbankMapper powerbanks;
    private final MovementRecorder movements;

    public LocalInventoryPort(CabinetPoolService pool, CabinetMapper cabinets, PowerbankMapper powerbanks,
                              MovementRecorder movements) {
        this.pool = pool;
        this.cabinets = cabinets;
        this.powerbanks = powerbanks;
        this.movements = movements;
    }

    @Override
    public String engine() {
        return "LOCAL";
    }

    @Override
    @Transactional
    public void inbound(String itemType, String itemNo, String warehouseNo, String qcNo, String intakeNo) {
        // 条件更新：设备须仍在库、未绑定、未出库 —— 质检后被人拿去出库了，这里要拦住而不是把它写回仓里
        int n = CABINET.equals(itemType)
                ? cabinets.update(null, new LambdaUpdateWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, itemNo)
                .eq(DevCabinet::getStatus, CabinetStatus.IN_STOCK.name()).isNull(DevCabinet::getLocationNo)
                .isNull(DevCabinet::getIssuedAgentNo).set(DevCabinet::getWarehouseNo, warehouseNo))
                : powerbanks.update(null, new LambdaUpdateWrapper<DevPowerbank>().eq(DevPowerbank::getPowerbankNo, itemNo)
                .eq(DevPowerbank::getStatus, PowerbankStatus.IN_STOCK.name()).isNull(DevPowerbank::getCabinetNo)
                .set(DevPowerbank::getWarehouseNo, warehouseNo));
        if (n == 0) throw BizException.conflict("error.common.state_changed");
        movements.record(new MovementRecorder.Move(itemType, itemNo, MovementEvent.INBOUND, null,
                MovementRecorder.wh(warehouseNo), null, null, "质检凭证 " + qcNo, intakeNo));
    }

    @Override
    public List<Cabinet> dispatch(String agentNo, String siteNo, String receiverNo, List<String> cabinetNos) {
        return pool.issue(agentNo, siteNo, receiverNo, cabinetNos);
    }

    @Override
    public List<Cabinet> claim(List<String> cabinetNos) {
        return pool.claim(cabinetNos);
    }

    @Override
    public List<Cabinet> redirect(List<String> cabinetNos, String agentNo, String siteNo, String receiverNo, String reason) {
        return pool.redirect(cabinetNos, agentNo, siteNo, receiverNo, reason);
    }

    @Override
    public List<Cabinet> returnToWarehouse(List<String> cabinetNos, String warehouseNo, String reason) {
        return pool.returnToWarehouse(cabinetNos, warehouseNo, reason);
    }
}
