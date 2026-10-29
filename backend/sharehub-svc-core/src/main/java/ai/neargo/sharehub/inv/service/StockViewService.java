package ai.neargo.sharehub.inv.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.inv.dto.StockDtos.InventoryEngine;
import ai.neargo.sharehub.inv.dto.StockDtos.StockCount;
import ai.neargo.sharehub.inv.dto.StockDtos.StockCountDetail;
import ai.neargo.sharehub.inv.dto.StockDtos.StockLedgerRow;

import java.util.List;

/**
 * 库存流水与盘点（S4，D §2.4 ⑥）。流水两个引擎都有（本地取进出仓履历）；盘点是独立进销存的单据，
 * 本地引擎下 409（本地没有盘点账）。
 */
public interface StockViewService {

    InventoryEngine engine();

    /** 统一库存视图（S6）：取本地投影，两个引擎同口径。 */
    List<ai.neargo.sharehub.inv.dto.StockDtos.StockStage> stages();

    /** 期间报表（S6）：SERVICE 读库存服务期间汇总；LOCAL 由当前在仓数与履历倒推（履历自 V196 起）。 */
    List<ai.neargo.sharehub.inv.dto.StockDtos.StockPeriodRow> periodSummary(java.time.LocalDate from, java.time.LocalDate to,
                                                                          String warehouseNo);

    PageResult<StockLedgerRow> ledger(String warehouseNo, String itemType, String keyword, Integer page, Integer size);

    List<StockCount> counts(String warehouseNo, String status);

    StockCountDetail openCount(String warehouseNo);

    StockCountDetail count(String countNo);

    StockCountDetail scan(String countNo, List<String> serialNos);

    StockCountDetail submit(String countNo);

    StockCountDetail approve(String countNo, String note);

    StockCountDetail reject(String countNo, String note);

    StockCountDetail post(String countNo);

    StockCountDetail cancel(String countNo);
}
