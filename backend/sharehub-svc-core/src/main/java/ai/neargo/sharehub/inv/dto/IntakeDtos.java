package ai.neargo.sharehub.inv.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 到仓待检（V192）出入参。 */
public final class IntakeDtos {
    private IntakeDtos() {
    }

    /** 列表行：单头 + 明细计数。 */
    public record Intake(String intakeNo, String kind, String warehouseNo, String warehouseName, String sourceRef,
                         LocalDateTime arrivedAt, String status, String note,
                         int total, int pending, int passed, int failed, int inbound, int disposed,
                         String createdBy, LocalDateTime createdAt) {
    }

    public record IntakeItem(String itemType, String itemNo, String sn, String model, String qcNo, String qcResult,
                             String disposition, String dispositionNote, String handledBy, LocalDateTime handledAt) {
    }

    public record IntakeDetail(Intake intake, List<IntakeItem> items) {
    }

    /** 到货登记：仓 + 设备号（柜与宝分开列）。arrivedAt 为空取现在。 */
    public record CreateReq(String warehouseNo, String sourceRef, String arrivedAt, String note,
                            List<String> cabinetNos, List<String> powerbankNos) {
    }

    public record ItemRef(String itemType, String itemNo) {
    }

    public record ItemsReq(List<ItemRef> items) {
    }

    /** 不合格处置：RETURN_SUPPLIER / SCRAP，说明必填。 */
    public record DisposeReq(List<ItemRef> items, String disposition, String note) {
    }
}
