package ai.neargo.sharehub.dev.service;

import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;

import java.util.List;

/**
 * 设备出库池（V161；用户 2026-10-28：绑定商家先出库，出库必须选商家，绑定只从该商家的出库池里选）。
 *
 * <p>库存（仓库、调拨单）暂缓，可能由独立服务实现。这里只在设备上记「出库给了哪个商家」：
 * {@link #issue} 与 {@link #returnToWarehouse} 是设备上的直接动作，将来库存服务签收出库单时调同一入口。
 *
 * <p>两个动作都是**整批一个事务**：任一台不满足条件，整批不动，并说清是哪一台、为什么。
 */
public interface CabinetPoolService {

    /**
     * 出库给商家：设备离开仓库，进入该商家的出库池。
     *
     * <p>每台须：在库（IN_STOCK）、未绑定点位、未在任何出库池、质检放行、未归档。
     * 商家须启用且已补齐位置与行业。
     */
    List<Cabinet> issue(String agentNo, List<String> cabinetNos);

    /**
     * 出库池退回仓库：只收出库池里**未绑定**的设备；已绑定的先解绑。
     *
     * @param warehouseNo 回到哪个仓；可空（库存服务接入前，仓号由线下单据记录）
     */
    List<Cabinet> returnToWarehouse(List<String> cabinetNos, String warehouseNo, String reason);

    /** 出库池按商家汇总：未绑定 / 已绑定未上线 / 已上线。{@code agentNo} 为空 = 全部商家。 */
    List<IssuedSummary> summary(String agentNo);

    record IssuedSummary(String agentNo, String agentName, int unbound, int bound, int live) {
    }
}
