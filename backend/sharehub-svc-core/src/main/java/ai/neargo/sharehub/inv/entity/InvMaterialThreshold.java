package ai.neargo.sharehub.inv.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 物料安全库存（inv_material_threshold，V210）：仓 × 物料，0 = 不预警。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inv_material_threshold")
public class InvMaterialThreshold extends BaseEntity {

    private String warehouseNo;
    private String materialNo;
    private Integer minQty;
}
