package ai.neargo.powerbank.wo.entity;

import ai.neargo.powerbank.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 工单实体（wo_order）。镜像 Dto.WorkOrder（Dto.createdAt→woCreatedAt 保留展示值）；主键/审计列见 {@link BaseEntity}。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wo_order")
public class WoOrder extends BaseEntity {
    private String woNo;
    private String type;
    private String source;
    private String priority;
    private String cabinetNo;
    private String locationName;
    private String status;
    private String assigneeName;
    private String slaDueAt;
    private String description;
    private String woCreatedAt;
}
