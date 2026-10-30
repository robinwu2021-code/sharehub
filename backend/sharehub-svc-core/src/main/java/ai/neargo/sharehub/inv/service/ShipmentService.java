package ai.neargo.sharehub.inv.service;

import ai.neargo.sharehub.inv.ShipmentDocType;
import ai.neargo.sharehub.inv.dto.ShipmentDtos.ShipInfo;
import ai.neargo.sharehub.inv.dto.ShipmentDtos.Shipment;

import java.util.List;

/** 发货物流（V205）：随调拨发出 / 出库给商家 / 退供应商记一条，可按单据或设备号查。 */
public interface ShipmentService {

    /** 先校验再做发出动作 —— 免得动作成功了物流因格式问题记不上。 */
    void validate(ShipInfo info);

    /** 承运商与运单号都空返回 null（不记）。itemsByType：设备类型 → 设备号（一个包裹里柜与宝可以混装）。 */
    Shipment record(ShipmentDocType docType, String docRef, java.util.Map<String, List<String>> itemsByType, ShipInfo info);

    List<Shipment> list(String docType, String docRef, String itemNo);
}
