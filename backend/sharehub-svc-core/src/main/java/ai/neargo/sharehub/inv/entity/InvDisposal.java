package ai.neargo.sharehub.inv.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 处置出库单（inv_disposal，V203）：在库设备报废 / 退供应商，建单即过账。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inv_disposal")
public class InvDisposal extends BaseEntity {

    private String disposalNo;
    /** {@link ai.neargo.sharehub.inv.DisposalKind}。 */
    private String kind;
    private String warehouseNo;
    private String warehouseName;
    private String supplierNo;
    private String supplierName;
    /** {@link ai.neargo.sharehub.inv.DisposalReason}，SCRAP 必填。 */
    private String reasonCode;
    private String note;
    private Integer itemCount;
}
