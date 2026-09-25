package ai.neargo.sharehub.agent.ext.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/** 代理清退单（agt_exit，V110）。 */
@Data
@TableName("agt_exit")
public class AgtExit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String exitNo;
    private String tenantId;
    private String agentNo;
    /** {@link ai.neargo.sharehub.agent.ext.AgentExitStatus}。 */
    private String status;
    private String reason;
    private String startedBy;
    private LocalDateTime startedAt;
    private LocalDateTime reclaimedAt;
    private LocalDateTime settledAt;
    private LocalDateTime closedAt;
    private String closedBy;
    private LocalDateTime createdAt;
    @Version
    private Long version;
}
