package ai.neargo.sharehub.api.core.port;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 库存侧的系统读（业务告警判定用，A2 / A3）。<b>豁免数据范围</b>：判定是全局的。
 */
public interface InventoryStatePort {

    /** 到达早于 {@code before} 且仍未处理完的到仓待检单。 */
    List<OpenIntake> openIntakesArrivedBefore(LocalDateTime before, int limit);

    record OpenIntake(String intakeNo, String kind, String warehouseNo, String sourceRef, LocalDateTime arrivedAt, int pending) {
    }

    /** 给定撤机工单号里，已经生成回仓调拨单的那些。 */
    Set<String> removalsWithReturnTransfer(Collection<String> woNos);
}
