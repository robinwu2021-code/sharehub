package ai.neargo.sharehub.inv.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 库存管理 · 物料（V210）出入参：与 ops-web lib/types/inventory.ts 同名同字段。 */
public final class MaterialDtos {
    private MaterialDtos() {
    }

    /** builtin = 机柜、充电宝（只读，走设备流程）；hasMovements = 已有物料单据，跟踪方式与单位此后不可改。 */
    public record Material(String materialNo, String name, String category, String trackingMode, String uom, String spec,
                           String vendorCode, String defaultSupplierNo, boolean builtin, boolean hasMovements,
                           LocalDateTime archivedAt, LocalDateTime createdAt) {
    }

    public record MaterialReq(String name, String category, String trackingMode, String uom, String spec, String vendorCode,
                              String defaultSupplierNo) {
    }

    /** 数量物料填 qty；逐件物料 qty = serialNos 条数。 */
    public record MaterialDocLine(String materialNo, String materialName, Integer qty, List<String> serialNos, BigDecimal unitCost) {
    }

    public record MaterialDoc(String docNo, String kind, String purpose, String warehouseNo, String warehouseName,
                              String toWarehouseNo, String toWarehouseName, String supplierNo, String supplierName,
                              String reasonCode, String receiverNo, String woNo, String currency, String note, int totalQty,
                              List<MaterialDocLine> lines, String createdBy, LocalDateTime createdAt) {
    }

    public record MaterialDocReq(String purpose, String warehouseNo, String toWarehouseNo, String supplierNo, String reasonCode,
                                 String receiverNo, String woNo, String currency, String note, List<MaterialDocLine> lines) {
    }

    public record MaterialBalance(String warehouseNo, String warehouseName, String materialNo, String materialName,
                                  String category, String trackingMode, String uom, int onHand, int minQty, boolean low,
                                  List<String> serialNos) {
    }

    public record MaterialMinQtyReq(String warehouseNo, String materialNo, Integer minQty) {
    }
}
