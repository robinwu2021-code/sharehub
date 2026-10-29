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

    public record OpenCountReq(String warehouseNo) {
    }

    public record CountScanReq(List<String> serialNos) {
    }

    public record CountNoteReq(String note) {
    }
}
