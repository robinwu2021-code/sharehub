package ai.neargo.sharehub.inv.remote;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 对接独立进销存的映射与游标（V198 inventory_sync_state）。 */
@Data
@TableName("inventory_sync_state")
public class InventorySyncState {
    @TableId(type = IdType.INPUT)
    private String k;
    private String v;
    private LocalDateTime updatedAt;
}
