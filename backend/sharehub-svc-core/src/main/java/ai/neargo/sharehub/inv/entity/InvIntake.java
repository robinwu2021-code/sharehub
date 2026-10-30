package ai.neargo.sharehub.inv.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 到仓待检单（inv_intake，V192）。 */
@Data
@TableName("inv_intake")
public class InvIntake {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String intakeNo;
    private String tenantId;
    /** ARRIVAL / RETURN。 */
    private String kind;
    private String warehouseNo;
    /** 供应商（V201，到货登记可选）与名称快照。 */
    private String supplierNo;
    private String supplierName;
    /** 单价币种（V202）。 */
    private String currency;
    private String warehouseName;
    private String sourceRef;
    /** 期初依据（V214 列）：期初建账入库单必填，随入库带给库存服务（生产档案 OPENING 要求依据）。 */
    private String openingBasis;
    private LocalDateTime arrivedAt;
    /** OPEN / DONE。 */
    private String status;
    private String note;
    private LocalDateTime createdAt;
    private String createdBy;
}
