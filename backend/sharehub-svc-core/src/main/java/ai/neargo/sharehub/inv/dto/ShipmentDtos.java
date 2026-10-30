package ai.neargo.sharehub.inv.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 发货物流（V205）。 */
public final class ShipmentDtos {
    private ShipmentDtos() {
    }

    /** 随发出动作一起提交；承运商与运单号都空 = 不记（自提 / 当面交付）。shippedAt 空取现在。 */
    public record ShipInfo(String carrier, String trackingNo, String shippedAt, String note) {
    }

    public record Shipment(String shipmentNo, String docType, String docRef, String carrier, String trackingNo,
                           LocalDateTime shippedAt, String note, List<String> itemNos, String createdBy) {
    }
}
