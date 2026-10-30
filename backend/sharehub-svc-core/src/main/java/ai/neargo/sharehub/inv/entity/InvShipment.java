package ai.neargo.sharehub.inv.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 发货物流（inv_shipment，V205）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inv_shipment")
public class InvShipment extends BaseEntity {

    private String shipmentNo;
    /** {@link ai.neargo.sharehub.inv.ShipmentDocType}。 */
    private String docType;
    private String docRef;
    private String carrier;
    private String trackingNo;
    private LocalDateTime shippedAt;
    private String note;
    private Integer itemCount;
}
