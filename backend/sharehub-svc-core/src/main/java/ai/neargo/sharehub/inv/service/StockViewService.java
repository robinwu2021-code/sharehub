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
