package ai.neargo.sharehub.dev.movement;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 设备履历一行（V196，只追加）。 */
@Data
@TableName("dev_movement_log")
public class DevMovementLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    /** CABINET / POWERBANK。 */
    private String itemType;
    private String itemNo;
    /** {@link MovementEvent}。 */
    private String event;
    private String fromRef;
    private String toRef;
    private String agentBefore;
    private String agentAfter;
    private String reason;
    private String sourceRef;
    private String scopeRegionId;
    private String scopeSiteNo;
    private String scopeAgentNo;
    private String operatorNo;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
}
