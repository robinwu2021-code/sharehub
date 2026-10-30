package ai.neargo.sharehub.inv.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 物料单据宿主记录（inv_material_doc，V210）：账在库存服务，这里存单头与对方单号。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inv_material_doc")
public class InvMaterialDoc extends BaseEntity {

    private String docNo;
    private String kind;
    private String purpose;
    private String warehouseNo;
    private String warehouseName;
    private String toWarehouseNo;
    private String toWarehouseName;
    private String supplierNo;
    private String supplierName;
    private String reasonCode;
    private String receiverNo;
    private String woNo;
    private String currency;
    private String note;
    private Integer totalQty;
    private String remoteOutNo;
    private String remoteInNo;
    private String operationNo;
}
