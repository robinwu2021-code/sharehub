package ai.neargo.sharehub.inv.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 安全库存（inv_stock_threshold，V204）：仓 × 设备类型的下限，0 = 不预警。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inv_stock_threshold")
public class InvStockThreshold extends BaseEntity {

    private String warehouseNo;
    /** CABINET / POWERBANK。 */
    private String itemType;
    private Integer minQty;
}
