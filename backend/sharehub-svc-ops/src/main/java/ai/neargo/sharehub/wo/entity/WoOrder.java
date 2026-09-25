package ai.neargo.sharehub.wo.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 工单实体（wo_order）。镜像 WorkOrder（Dto.createdAt→woCreatedAt 保留展示值）；主键/审计列见 {@link BaseEntity}。 */
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

    // —— 2026-09-25 业务告警开单：合并键写在 source_ref 上，**插入时一并写** ——
    // 此前 source_ref 由 insert 之后的第二条 UPDATE 补写：并发撞唯一键时第一条 insert 已落地，
    // 留下一张 source_ref 为空的孤儿单。放进实体后，撞键发生在 insert 本身，整行不落。
    private String sourceRef;
    private String siteNo;
    private String agentNo;
    /** PASSED / FAILED（仅告警来源的工单）。 */
    private String reviewStatus;
}
