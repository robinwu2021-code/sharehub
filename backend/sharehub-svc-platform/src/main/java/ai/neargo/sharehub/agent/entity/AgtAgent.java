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
    private Integer cabinetCount;
    private String status;

    /** 归档时间；null=在用。**不是 deleted** —— 归档是业务停用、可恢复，见 Archivable。 */
    private java.time.LocalDateTime archivedAt;
}
