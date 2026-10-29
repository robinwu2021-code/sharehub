package ai.neargo.sharehub.inv.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 库存流水与盘点（S4）。本地 / 独立进销存两个引擎同一形状。 */
public final class StockDtos {

    private StockDtos() {
    }

    /** 当前库存引擎：LOCAL / SERVICE；SERVICE 时 configured 表示凭证已配。 */
    public record InventoryEngine(String engine, boolean configured) {
    }

    /** 一条库存流水：qtyDelta 进仓 +、出仓 −；docKind 本地是履历事件、进销存是单据类型。 */
    public record StockLedgerRow(String id, String warehouseNo, String itemType, String itemNo, String docKind, String docNo,
                                 int qtyDelta, LocalDateTime occurredAt, String operator) {
    }

    public record StockCount(String countNo, String warehouseNo, String status, LocalDateTime openedAt) {
    }

    public record StockCountLine(String itemType, int bookQty, Integer countedQty, Integer diffQty) {
    }

    public record StockCountScan(String serialNo, String verdict) {
    }

    public record StockCountDetail(StockCount count, List<StockCountLine> lines, List<StockCountScan> exceptions,
                                   List<String> missingSerials) {
    }

    /**
     * 统一库存视图（S6）：按生命周期阶段的台数。阶段码：INTAKE 到仓待检 · IN_STOCK 在仓 · IN_TRANSIT 在途 ·
     * DISPATCHED 已发货待签收 · ISSUED 已交付待绑定 · BOUND 已绑定待上线 · LIVE 投放中（宝：在柜）· RENTED 借出 ·
     * FAULT 故障 · LOST 遗失 · RETIRED 已离开（报废 / 售出）。
     */
    public record StockStage(String stage, long cabinets, long powerbanks) {
    }

    /** 期间报表一行（S6）：期初 + 入 − 出 = 期末；入 / 出按原因（本地为履历事件，进销存为单据原因）。 */
    public record StockPeriodRow(String warehouseNo, String itemType, long opening, long inQty, long outQty, long closing,
                                 java.util.Map<String, Long> inByReason, java.util.Map<String, Long> outByReason) {
    }

    /** 存量迁移一批（S7）：最多 limit 台，缺省 200。 */
    public record MigrationRunReq(Integer limit) {
    }

    public record OpenCountReq(String warehouseNo) {
    }

    public record CountScanReq(List<String> serialNos) {
    }

    public record CountNoteReq(String note) {
    }
}
