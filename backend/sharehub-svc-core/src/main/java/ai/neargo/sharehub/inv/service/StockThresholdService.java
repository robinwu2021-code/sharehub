package ai.neargo.sharehub.inv.service;

import ai.neargo.sharehub.inv.dto.ThresholdDtos.StockThreshold;
import ai.neargo.sharehub.inv.dto.ThresholdDtos.StockThresholdReq;

import java.util.List;

/**
 * 安全库存（V204）。可用量 = 在该仓在库、未绑点位、不在出库池（宝：不在柜内）、未归档 —— 即「现在能发出去的」。
 * 低于下限由告警 LOW_STOCK 给仓管待办（InventoryEvaluator）。
 */
public interface StockThresholdService {

    /** 每个启用中的仓 × 柜 / 宝各一行（未设下限也列出，便于直接填）。 */
    List<StockThreshold> list(String warehouseNo);

    StockThreshold save(StockThresholdReq req);

    /** 低于下限的行（告警判定用，豁免数据范围）。 */
    List<StockThreshold> lowRows();
}
