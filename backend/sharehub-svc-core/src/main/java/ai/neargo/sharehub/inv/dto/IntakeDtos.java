package ai.neargo.sharehub.inv.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 到仓待检（V192）出入参。 */
public final class IntakeDtos {
    private IntakeDtos() {
    }

    /** 列表行：单头 + 明细计数。 */
    public record Intake(String intakeNo, String kind, String warehouseNo, String warehouseName, String sourceRef,
                         String supplierNo, String supplierName, String currency, java.math.BigDecimal totalCost,
                         LocalDateTime arrivedAt, String status, String note,
                         int total, int pending, int passed, int failed, int inbound, int disposed,
                         String createdBy, LocalDateTime createdAt) {
    }

    public record IntakeItem(String itemType, String itemNo, java.math.BigDecimal unitCost, String sn, String model, String qcNo, String qcResult,
                             String disposition, String dispositionNote, String handledBy, LocalDateTime handledAt) {
    }

    public record IntakeDetail(Intake intake, List<IntakeItem> items) {
    }

    /**
     * 入库单登记：仓 + 设备号（柜与宝分开列）。arrivedAt 为空取现在。
     * kind：ARRIVAL 采购到货（默认，走质检）/ OPENING 期初建账（登记即入库）。
     * unitCosts：设备号 → 单价（可只填部分），币种 currency。
     */
    public record CreateReq(String kind, String warehouseNo, String supplierNo, String sourceRef, String arrivedAt, String note,
                            String currency, java.util.Map<String, java.math.BigDecimal> unitCosts,
                            List<String> cabinetNos, List<String> powerbankNos, String openingBasis) {
    }

    public record ItemRef(String itemType, String itemNo) {
    }

    public record ItemsReq(List<ItemRef> items) {
    }

    /** 不合格处置：RETURN_SUPPLIER / SCRAP，说明必填。 */
    public record DisposeReq(List<ItemRef> items, String disposition, String note) {
    }
}
