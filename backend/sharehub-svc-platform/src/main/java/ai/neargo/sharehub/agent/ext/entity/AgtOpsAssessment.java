package ai.neargo.sharehub.agent.ext.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 代理运维月度考核（agt_ops_assessment，V110）。 */
@Data
@TableName("agt_ops_assessment")
public class AgtOpsAssessment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String agentNo;
    private String period;
    private String applyPeriod;
    private Integer woTotal;
    private Integer woInSla;
    private BigDecimal slaRate;
    private BigDecimal onlineRate;
    private Integer complaints;
    private Integer takenOver;
    private BigDecimal coefficient;
    private LocalDateTime computedAt;
    private LocalDateTime createdAt;
}
