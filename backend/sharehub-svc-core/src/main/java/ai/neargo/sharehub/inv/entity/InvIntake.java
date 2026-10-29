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
    private String warehouseName;
    private String sourceRef;
    private LocalDateTime arrivedAt;
    /** OPEN / DONE。 */
    private String status;
    private String note;
    private LocalDateTime createdAt;
    private String createdBy;
}
