package ai.neargo.sharehub.inv.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 库存报表（V206，GET /api/ops/reports/inventory）。 */
public final class InventoryReportDtos {
    private InventoryReportDtos() {
    }

    /**
     * @param stages    全局阶段分布（同库存总览）
     * @param rows      各仓 × 类型：期初 / 入 / 出 / 期末（期间口径同期间报表）+ 当前可用量与安全库存
     * @param disposals 期间处置出库，按「类型:原因」计台数（退供应商无原因，记 RETURN_SUPPLIER）
     * @param purchases 期间入库单金额，按币种
     */
    public record InventoryReport(String from, String to, List<StockDtos.StockStage> stages, List<WarehouseRow> rows,
                                  Map<String, Long> disposals, List<PurchaseRow> purchases) {
    }

    public record WarehouseRow(String warehouseNo, String warehouseName, String itemType, long opening, long inQty, long outQty,
                               long closing, int available, int minQty, boolean low) {
    }

    public record PurchaseRow(String currency, long qty, BigDecimal amount) {
    }
}
