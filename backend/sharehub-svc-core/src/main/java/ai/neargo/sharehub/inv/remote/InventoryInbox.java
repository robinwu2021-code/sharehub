package ai.neargo.sharehub.inv.remote;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 库存事件收件箱（V198 inventory_inbox）：event_id 唯一，重复投递只处理一次。 */
@Data
@TableName("inventory_inbox")
public class InventoryInbox {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private Long seq;
    private String type;
    private String resourceId;
    private Integer resourceVersion;
    private String payload;
    /** DONE / FAILED。 */
    private String status;
    private String error;
    private LocalDateTime processedAt;
    private LocalDateTime createdAt;
}
