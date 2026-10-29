package ai.neargo.sharehub.inv.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 处置出库（V203）出入参。 */
public final class DisposalDtos {
    private DisposalDtos() {
    }

    public record Disposal(String disposalNo, String kind, String warehouseNo, String warehouseName, String supplierNo,
                           String supplierName, String reasonCode, String note, int itemCount, String createdBy,
                           LocalDateTime createdAt) {
    }

    public record DisposalItem(String itemType, String itemNo) {
    }

    public record DisposalDetail(Disposal disposal, List<DisposalItem> items) {
    }

    /** 建单即过账。SCRAP 须 reasonCode；RETURN_SUPPLIER 须 supplierNo。 */
    public record DisposalReq(String kind, String warehouseNo, String supplierNo, String reasonCode, String note,
                              List<String> cabinetNos, List<String> powerbankNos) {
    }
}
