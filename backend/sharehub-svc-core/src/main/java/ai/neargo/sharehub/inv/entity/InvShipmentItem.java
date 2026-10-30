package ai.neargo.sharehub.inv.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 发货物流明细（V205）。 */
@Data
@TableName("inv_shipment_item")
public class InvShipmentItem {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String shipmentNo;
    private String itemType;
    private String itemNo;
    private LocalDateTime createdAt;
}
