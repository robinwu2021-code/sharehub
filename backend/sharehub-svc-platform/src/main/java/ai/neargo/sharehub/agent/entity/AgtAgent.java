package ai.neargo.sharehub.agent.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 代理商实体（agt_agent，ADR-012）。镜像 Agent；主键/隔离键/审计列见 {@link BaseEntity}。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agt_agent")
public class AgtAgent extends BaseEntity implements ai.neargo.sharehub.common.crud.Archivable {
    private String agentNo;
    private String name;
    private String contact;
    private String regionScope;
    private Double shareRate;
    /*
     * 这里**没有** cabinetCount：同 LocVenue，机柜数是聚合值不是属性。
     * shareRate 保留 —— 它是档案上的真实配置（V42 补列），
     * 但**分账以 share_rule 为准**，别拿这一列去算钱。
     */
    private String status;

    /** 归档时间；null=在用。**不是 deleted** —— 归档是业务停用、可恢复，见 Archivable。 */
    private java.time.LocalDateTime archivedAt;
}
