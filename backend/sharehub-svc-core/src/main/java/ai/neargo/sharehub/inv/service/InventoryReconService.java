package ai.neargo.sharehub.inv.service;

import ai.neargo.sharehub.inv.dto.ReconDtos.Report;

/** 库存对账（对接明细 F07）：只报差异、给处置建议，不自动用任何一边覆盖另一边。 */
public interface InventoryReconService {

    /** 给 warehouseNo 只核这个仓的在仓部分；给 agentNo 只核这个商家的出库池；都空 = 两部分全核。 */
    Report run(String warehouseNo, String agentNo);
}
